/* Makes unoccupied reading-page space respond without taking over document gestures. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Opens the document end below its final block, or the retained caret elsewhere on the page. */
internal fun Modifier.editOnMarkdownBackgroundTap(
    session: EditorSession,
    revision: Long
): Modifier = if (session.isViewOnly) {
    this
} else {
    pointerInput(session, revision) {
        awaitEachGesture {
            val down =
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Read this before the scrollable sees Down and stops an existing fling.
            val wasScrolling = session.markdownPreviewListState.isScrollInProgress
            val initial = awaitPointerEvent(PointerEventPass.Final)
            if (wasScrolling || initial.changes.size != 1 ||
                initial.changes.any { it.isConsumed }
            ) {
                return@awaitEachGesture
            }
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var change = down
                while (change.pressed) {
                    // Children get first refusal: links, Copy, selection, and both scroll axes.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.size != 1) return@withTimeoutOrNull null
                    change = event.changes.single()
                    if (
                        change.id != down.id || change.isConsumed ||
                        (change.position - down.position).getDistance() >
                        viewConfiguration.touchSlop ||
                        change.position.x !in 0f..size.width.toFloat() ||
                        change.position.y !in 0f..size.height.toFloat()
                    ) {
                        return@withTimeoutOrNull null
                    }
                }
                change
            }
            if (up != null) {
                val metrics = session.state.metrics
                if (metrics != null && session.isBelowFinalMarkdownItem(revision, up.position.y)) {
                    session.showTextEditorAtSource(
                        revision = revision,
                        utf16Offset = metrics.utf16Length
                    )
                } else {
                    session.showTextEditor()
                }
                up.consume()
            }
        }
    }
}

/** Distinguishes the trailing page from gutters and gaps without measuring off-screen content. */
private fun EditorSession.isBelowFinalMarkdownItem(revision: Long, y: Float): Boolean {
    val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready ?: return false
    if (ready.revision != revision) return false
    val itemCount = ready.layout.items.size
    if (itemCount == 0) return true
    val layout = markdownPreviewListState.layoutInfo
    if (layout.totalItemsCount != itemCount) return false
    val last = layout.visibleItemsInfo.lastOrNull { item -> item.index == itemCount - 1 }
        ?: return false
    // Item offsets exclude leading content padding; pointer coordinates include it.
    val bottom = last.offset.toFloat() + last.size - layout.viewportStartOffset
    return y >= bottom
}
