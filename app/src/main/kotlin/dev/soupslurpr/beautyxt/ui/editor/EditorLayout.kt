/* Defines editor viewport geometry and input-preserving layout policies. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.ui.UiText
import kotlinx.coroutines.flow.first

private const val VIEWPORT_LOADING_ITEM_KEY_PREFIX = "viewport-loading"

private const val VIEWPORT_PREFETCH_BLOCK_COUNT = 8

private const val EDIT_WINDOW_PREFETCH_VIEWPORTS = 1

private const val MAX_HORIZONTAL_EDITOR_ACTION_FONT_SCALE = 1.3f

private const val MAX_LINE_NUMBER_GUTTER_WIDTH_FRACTION = 0.25f

private const val MAX_RECOVERY_PANEL_HEIGHT_FRACTION = 0.45f

private val MaximumRecoveryPanelHeight = 320.dp

private val MinimumRecoveryPanelHeight = 96.dp

private val MinimumEditableFieldHeight = 128.dp

private val CompactEditorChromeWidth = 480.dp

private val MinimumExpandedEditorHeight = 480.dp

private val MinimumInlineDocumentWidth = 160.dp

private val MinimumLineNumberGutterWidth = 40.dp

/** Returns whether the visible range is close enough to prefetch earlier blocks. */
internal fun shouldPrefetchPreviousViewport(
    firstVisibleBlockIndex: Int?,
    blockCount: Int,
    prefetchBlockCount: Int = VIEWPORT_PREFETCH_BLOCK_COUNT
): Boolean {
    require(blockCount >= 0) { "viewport block count must be nonnegative" }
    require(prefetchBlockCount > 0) { "viewport prefetch block count must be positive" }
    if (firstVisibleBlockIndex == null) {
        return false
    }
    require(firstVisibleBlockIndex in 0 until blockCount) {
        "first visible block must belong to the viewport cache"
    }
    return firstVisibleBlockIndex < prefetchBlockCount
}

/** Returns whether the visible range is close enough to prefetch later blocks. */
internal fun shouldPrefetchNextViewport(
    lastVisibleBlockIndex: Int?,
    blockCount: Int,
    prefetchBlockCount: Int = VIEWPORT_PREFETCH_BLOCK_COUNT
): Boolean {
    require(blockCount >= 0) { "viewport block count must be nonnegative" }
    require(prefetchBlockCount > 0) { "viewport prefetch block count must be positive" }
    if (lastVisibleBlockIndex == null) {
        return false
    }
    require(lastVisibleBlockIndex in 0 until blockCount) {
        "last visible block must belong to the viewport cache"
    }
    return blockCount - lastVisibleBlockIndex <= prefetchBlockCount
}

/** Returns a cursor-specific identity for one transient loading row. */
internal fun viewportLoadingItemKey(paginationKey: String): String {
    require(paginationKey.isNotBlank()) { "viewport pagination key must not be blank" }
    return "$VIEWPORT_LOADING_ITEM_KEY_PREFIX:$paginationKey"
}

/** Returns whether the editor chrome needs icon-only direct actions. */
internal fun usesCompactEditorChromeActions(availableWidth: Dp, fontScale: Float): Boolean {
    require(availableWidth >= 0.dp) { "available chrome width must not be negative" }
    require(fontScale > 0f) { "font scale must be positive" }
    return availableWidth < CompactEditorChromeWidth ||
        fontScale > MAX_HORIZONTAL_EDITOR_ACTION_FONT_SCALE
}

/** Returns whether large text has enough vertical room for an expanded app bar. */
internal fun usesExpandedEditorTopBar(fontScale: Float, availableHeight: Dp): Boolean {
    require(fontScale.isFinite() && fontScale > 0f) {
        "editor top bar font scale must be positive and finite"
    }
    require(availableHeight >= 0.dp) { "available editor height must not be negative" }
    return fontScale > MAX_HORIZONTAL_EDITOR_ACTION_FONT_SCALE &&
        availableHeight >= MinimumExpandedEditorHeight
}

/** Returns whether an embedded editor should apply its own predictive back motion. */
internal fun usesEditorPredictiveBackMotion(
    closesDocumentTask: Boolean,
    backClosesDocument: Boolean
): Boolean = !closesDocumentTask && backClosesDocument

/** Returns whether one Back event must dismiss a stationary visible IME first. */
internal fun reservesBackForIme(
    isImeVisible: Boolean,
    imeBottomInsetPixels: Int,
    imeTargetBottomInsetPixels: Int,
    imeDismissalRequested: Boolean
): Boolean {
    require(imeBottomInsetPixels >= 0) { "IME bottom inset must not be negative" }
    require(imeTargetBottomInsetPixels >= 0) {
        "IME target bottom inset must not be negative"
    }
    return isImeVisible &&
        imeBottomInsetPixels > 0 &&
        imeTargetBottomInsetPixels > 0 &&
        !imeDismissalRequested
}

/** Claims exactly one Back event while a visible IME begins dismissing. */
internal class ImeBackReservation {
    private var dismissalRequested = false

    /** Claims the current Back event before its asynchronous gesture flow begins. */
    fun tryClaim(
        isImeVisible: Boolean,
        imeBottomInsetPixels: Int,
        imeTargetBottomInsetPixels: Int
    ): Boolean {
        val claimed =
            reservesBackForIme(
                isImeVisible = isImeVisible,
                imeBottomInsetPixels = imeBottomInsetPixels,
                imeTargetBottomInsetPixels = imeTargetBottomInsetPixels,
                imeDismissalRequested = dismissalRequested
            )
        if (claimed) {
            dismissalRequested = true
        }
        return claimed
    }

    /** Releases the claim after cancellation or complete IME dismissal. */
    fun release() {
        dismissalRequested = false
    }
}

/** Returns whether the visible IME leaves landscape space only for focused editing. */
internal fun usesImeFocusLayout(
    imeBottomInsetPixels: Int,
    windowWidthPixels: Int,
    windowHeightPixels: Int
): Boolean {
    require(imeBottomInsetPixels >= 0) { "IME inset must not be negative" }
    require(windowWidthPixels >= 0) { "window width must not be negative" }
    require(windowHeightPixels >= 0) { "window height must not be negative" }
    return imeBottomInsetPixels > 0 && windowWidthPixels > windowHeightPixels
}

/** Returns the bounded height available to editor recovery messages. */
internal fun recoveryPanelMaxHeight(availableHeight: Dp): Dp {
    require(availableHeight >= 0.dp) { "available editor height must not be negative" }
    val heightAfterEditorReservation =
        (availableHeight - MinimumEditableFieldHeight).coerceAtLeast(0.dp)
    val preferredHeight =
        minOf(
            heightAfterEditorReservation,
            availableHeight * MAX_RECOVERY_PANEL_HEIGHT_FRACTION,
            MaximumRecoveryPanelHeight
        )
    return minOf(
        availableHeight,
        maxOf(MinimumRecoveryPanelHeight, preferredHeight)
    )
}

/** Identifies the neighboring bounded window that should be prefetched. */
internal enum class AutomaticEditWindowDirection {
    Earlier,
    Later
}

/** Returns the neighboring window reached within one visible viewport of an edge. */
internal fun automaticEditWindowDirection(
    scrollValuePixels: Int,
    maxScrollPixels: Int,
    viewportHeightPixels: Int,
    hasPrevious: Boolean,
    hasNext: Boolean
): AutomaticEditWindowDirection? {
    require(scrollValuePixels >= 0) { "edit-window scroll value must be nonnegative" }
    require(maxScrollPixels >= scrollValuePixels) {
        "edit-window maximum scroll must contain its value"
    }
    require(viewportHeightPixels > 0) { "edit-window viewport height must be positive" }
    if (maxScrollPixels == 0) {
        return null
    }
    val prefetchPixels = Math.multiplyExact(
        viewportHeightPixels,
        EDIT_WINDOW_PREFETCH_VIEWPORTS
    )
    val nearEarlierEdge = scrollValuePixels <= prefetchPixels
    val nearLaterEdge = maxScrollPixels - scrollValuePixels <= prefetchPixels
    return when {
        nearLaterEdge && hasNext &&
            (!nearEarlierEdge || scrollValuePixels >= maxScrollPixels / 2) ->
            AutomaticEditWindowDirection.Later

        nearEarlierEdge && hasPrevious -> AutomaticEditWindowDirection.Earlier

        else -> null
    }
}

/** Restores one bounded edit position without interrupting retained scroll momentum. */
internal suspend fun restoreEditWindowScroll(
    scrollState: ScrollState,
    targetScrollPixels: Int,
    preserveScrollMomentum: Boolean
) {
    require(targetScrollPixels >= 0) { "edit-window scroll target must not be negative" }
    // Clamp after layout has settled; an IME resize can change the measured bounds.
    val boundedTargetPixels = targetScrollPixels.coerceAtMost(scrollState.maxValue)
    if (preserveScrollMomentum) {
        scrollState.dispatchRawDelta((boundedTargetPixels - scrollState.value).toFloat())
    } else {
        scrollState.scrollTo(boundedTargetPixels)
    }
}

/** Returns whether the bounded field can retain interaction during its current operation. */
internal fun isActiveEditFieldEnabled(
    status: EditorDocumentStatus,
    preserveInputSession: Boolean
): Boolean = status != EditorDocumentStatus.LoadingInitial &&
    (
        status != EditorDocumentStatus.LoadingEditWindow ||
            preserveInputSession
        )

/** Returns whether the bounded field may apply one user-originated input change. */
internal fun canApplyActiveEditFieldInput(
    isClosePending: Boolean,
    canAcceptEditorInput: Boolean,
    hasPendingEditWindowAction: Boolean,
    documentCanAcceptInput: Boolean
): Boolean = !isClosePending &&
    canAcceptEditorInput &&
    !hasPendingEditWindowAction &&
    documentCanAcceptInput

/** Returns whether the bounded field must expose a non-editable IME contract. */
internal fun isActiveEditFieldReadOnly(
    canAcceptInput: Boolean,
    preserveInputSession: Boolean
): Boolean = !canAcceptInput && !preserveInputSession

/** Returns whether a retained editor field actually needs focus restoration. */
internal fun shouldRequestActiveEditFieldFocus(
    canAcceptInput: Boolean,
    shouldRestoreEditorFocus: Boolean,
    isEditorFocused: Boolean
): Boolean = canAcceptInput && shouldRestoreEditorFocus && !isEditorFocused

/** Returns the stable gutter width required by the widest logical line label. */
internal fun lineNumberGutterWidth(labelWidth: Dp): Dp {
    require(labelWidth >= 0.dp) { "line number label width must not be negative" }
    return maxOf(MinimumLineNumberGutterWidth, labelWidth + EditorCompactSpacing)
}

/** Returns whether an end-aligned line-number gutter leaves enough document width. */
internal fun usesLineNumberGutter(availableWidth: Dp, gutterWidth: Dp): Boolean {
    require(availableWidth >= 0.dp) { "available document width must not be negative" }
    require(gutterWidth >= 0.dp) { "line number gutter width must not be negative" }
    return gutterWidth <= availableWidth * MAX_LINE_NUMBER_GUTTER_WIDTH_FRACTION &&
        availableWidth - gutterWidth >= MinimumInlineDocumentWidth
}

/** Returns the accessible identity of one logical line render block. */
internal fun lineNumberDescription(logicalLine: Long, isContinuation: Boolean): UiText {
    require(logicalLine >= 0L) { "logical line must not be negative" }
    require(logicalLine < Long.MAX_VALUE) { "logical line must be displayable" }
    val displayLineNumber = logicalLine + 1L
    return UiText.Resource(
        if (isContinuation) R.string.source_line_continuation else R.string.source_line,
        listOf(displayLineNumber)
    )
}

/** Contains the first and last visible logical lines in visual order. */
internal data class VisibleLogicalLineRange(val firstLogicalLine: Long, val lastLogicalLine: Long) {
    init {
        require(firstLogicalLine >= 0L) { "first visible logical line must be nonnegative" }
        require(lastLogicalLine >= firstLogicalLine) {
            "last visible logical line must not precede the first"
        }
    }
}

/** Returns the first and last document lines resolved from visible lazy items. */
internal inline fun <VisibleItem> visibleLogicalLineRange(
    visibleItems: Iterable<VisibleItem>,
    logicalLineForItem: (VisibleItem) -> Long?
): VisibleLogicalLineRange? {
    var firstLogicalLine: Long? = null
    var lastLogicalLine: Long? = null
    for (visibleItem in visibleItems) {
        val logicalLine = logicalLineForItem(visibleItem) ?: continue
        require(logicalLine >= 0L) { "visible logical line must be nonnegative" }
        require(lastLogicalLine == null || logicalLine >= lastLogicalLine) {
            "visible logical lines must remain in document order"
        }
        if (firstLogicalLine == null) {
            firstLogicalLine = logicalLine
        }
        lastLogicalLine = logicalLine
    }
    return firstLogicalLine?.let { first ->
        VisibleLogicalLineRange(
            firstLogicalLine = first,
            lastLogicalLine = checkNotNull(lastLogicalLine)
        )
    }
}

/** Returns whether one measured lazy item occupies visible viewport pixels. */
internal fun lazyItemIntersectsViewport(
    itemOffset: Int,
    itemSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int
): Boolean {
    require(itemSize >= 0) { "lazy item size must not be negative" }
    require(viewportEndOffset >= viewportStartOffset) {
        "viewport end must not precede its start"
    }
    val itemEndOffset = Math.addExact(itemOffset, itemSize)
    return itemSize > 0 &&
        itemOffset < viewportEndOffset &&
        itemEndOffset > viewportStartOffset
}

/** Returns the block-local portion of one exact global Find match. */
internal fun findBlockHighlightRange(
    matchRange: Utf16Range?,
    blockUtf16Start: Long,
    blockUtf16End: Long
): TextRange? {
    require(blockUtf16Start >= 0L) { "block start must be nonnegative" }
    require(blockUtf16End >= blockUtf16Start) { "block end must not precede its start" }
    if (matchRange == null) {
        return null
    }
    val intersectionStart = maxOf(matchRange.start, blockUtf16Start)
    val intersectionEnd = minOf(matchRange.end, blockUtf16End)
    if (intersectionStart >= intersectionEnd) {
        return null
    }
    return TextRange(
        start = Math.toIntExact(intersectionStart - blockUtf16Start),
        end = Math.toIntExact(intersectionEnd - blockUtf16Start)
    )
}

/** Returns a one-based description of the visible document line range. */
internal fun visibleLogicalLineRangeDescription(
    visibleRange: VisibleLogicalLineRange,
    totalLineCount: Long
): UiText {
    require(totalLineCount > 0L) { "total line count must be positive" }
    require(visibleRange.lastLogicalLine < totalLineCount) {
        "visible logical line exceeds the document"
    }
    val firstDisplayLine = Math.incrementExact(visibleRange.firstLogicalLine)
    val lastDisplayLine = Math.incrementExact(visibleRange.lastLogicalLine)
    return if (firstDisplayLine == lastDisplayLine) {
        UiText.Resource(R.string.source_line_of_total, listOf(firstDisplayLine, totalLineCount))
    } else {
        UiText.Resource(
            R.string.source_lines_of_total,
            listOf(firstDisplayLine, lastDisplayLine, totalLineCount)
        )
    }
}

/** Describes one status surface anchored to the visible document viewport. */
internal sealed interface EditorViewportOverlay {
    /** Reports progress while an editable section opens. */
    data object OpeningSection : EditorViewportOverlay

    /** Reports progress while a bounded viewport opens at one logical line. */
    data class OpeningLine(val targetLogicalLine: Long) : EditorViewportOverlay {
        init {
            require(targetLogicalLine >= 0L) { "target logical line must be nonnegative" }
            require(targetLogicalLine < Long.MAX_VALUE) {
                "target logical line must be displayable"
            }
        }
    }

    /** Reports one retryable random-line failure without moving cached content. */
    data class LineFailure(val targetLogicalLine: Long) : EditorViewportOverlay {
        init {
            require(targetLogicalLine >= 0L) { "target logical line must be nonnegative" }
            require(targetLogicalLine < Long.MAX_VALUE) {
                "target logical line must be displayable"
            }
        }
    }

    /** Reports a sanitized edit-window failure without moving the viewport. */
    data class Failure(val message: UiText) : EditorViewportOverlay
}

/** Returns the edit-window status that must remain visible over cached blocks. */
internal fun editorViewportOverlay(
    status: EditorDocumentStatus,
    editorMessage: UiText?,
    lineViewportStatus: LineViewportStatus = LineViewportStatus.Idle
): EditorViewportOverlay? = when (lineViewportStatus) {
    is LineViewportStatus.Loading ->
        EditorViewportOverlay.OpeningLine(lineViewportStatus.targetLogicalLine)

    is LineViewportStatus.Failed ->
        EditorViewportOverlay.LineFailure(lineViewportStatus.targetLogicalLine)

    LineViewportStatus.Idle ->
        when {
            status == EditorDocumentStatus.LoadingEditWindow ->
                EditorViewportOverlay.OpeningSection

            editorMessage != null -> EditorViewportOverlay.Failure(editorMessage)

            else -> null
        }
}
