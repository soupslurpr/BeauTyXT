/* Keeps native source Copy/Cut exact when tabs have a different visual advance. */
package dev.soupslurpr.beautyxt.ui.editor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.TextRange

private const val COLLAPSE_COPY_GRACE_NANOS = 500_000_000L

/**
 * Compose copies its output text, including output-transformed characters. Record the source
 * selection while producing that output, before Copy collapses it or Cut removes it.
 * Once the selection collapses, the captured text expires after a brief Copy/Cut handoff window.
 * This object belongs to one bounded field, never to another document or the whole app.
 */
internal class SourceClipboardSelection(
    private val monotonicNanos: () -> Long = System::nanoTime
) {
    @Volatile private var source: String? = null
    @Volatile private var activeNonEmpty: Boolean = false
    @Volatile private var collapsedAtNanos: Long = 0L

    /** Returns whether an active or just-collapsed selection contains a tab display may expand. */
    val hasTabs: Boolean
        get() {
            val selected = currentCandidate() ?: return false
            return '\t' in selected
        }

    fun capture(text: CharSequence, selection: TextRange) {
        if (!selection.collapsed) {
            source = text.subSequence(selection.min, selection.max).toString()
            activeNonEmpty = true
            collapsedAtNanos = 0L
        } else if (activeNonEmpty) {
            activeNonEmpty = false
            collapsedAtNanos = monotonicNanos()
        }
    }

    /** Clears retained tab context when this field loses focus. */
    fun clear() {
        source = null
        activeNonEmpty = false
        collapsedAtNanos = 0L
    }

    fun original(display: CharSequence): CharSequence {
        val selected = currentCandidate() ?: return display
        return if (sourcePresentationMatches(display, selected)) selected else display
    }

    /** Returns the raw tab-preserving text only when [display] differs from the captured source. */
    fun originalIfChanged(display: CharSequence): CharSequence? {
        val selected = currentCandidate() ?: return null
        if (display.contentEquals(selected)) return null
        return if (sourcePresentationMatches(display, selected)) selected else null
    }

    private fun currentCandidate(): String? {
        val selected = source ?: return null
        if (activeNonEmpty) return selected
        val elapsed = monotonicNanos() - collapsedAtNanos
        if (elapsed in 0L..COLLAPSE_COPY_GRACE_NANOS) {
            return selected
        }
        source = null
        collapsedAtNanos = 0L
        return null
    }
}

/**
 * Restores raw source tabs inside a [ClipEntry] written by this field's Copy/Cut actions.
 *
 * Compose Foundation 1.14's `ClipboardPasteState` requires `LocalClipboard` to implement
 * [AndroidClipboard] (via `nativeClipboardManager`), which Compose annotates `@VisibleForTesting`.
 * Implementing [AndroidClipboard] directly keeps tab restoration scoped exclusively to
 * [setClipEntry] calls initiated by this `BasicTextField` without observing or rewriting unrelated
 * clipboard updates.
 */
@SuppressLint("VisibleForTests")
internal class SourceTabPreservingClipboard(
    private val delegate: AndroidClipboard,
    private val selection: SourceClipboardSelection
) : AndroidClipboard {
    override val clipboardManager: ClipboardManager
        get() = delegate.clipboardManager

    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        delegate.setClipEntry(restoreTabsInClipEntry(clipEntry, selection))
    }
}

/** Wraps [clipboard] when it is an [AndroidClipboard] so field-initiated Copy/Cut preserves tabs. */
@SuppressLint("VisibleForTests")
internal fun sourceTabPreservingClipboard(
    clipboard: Clipboard,
    selection: SourceClipboardSelection
): Clipboard =
    if (clipboard is AndroidClipboard) {
        SourceTabPreservingClipboard(delegate = clipboard, selection = selection)
    } else {
        clipboard
    }

internal fun restoreTabsInClipEntry(
    clipEntry: ClipEntry?,
    selection: SourceClipboardSelection
): ClipEntry? {
    if (clipEntry == null || !selection.hasTabs) return clipEntry
    val clip = clipEntry.clipData
    if (clip.itemCount != 1) return clipEntry
    val item = clip.getItemAt(0) ?: return clipEntry
    if (item.uri != null || item.intent != null || item.htmlText != null) return clipEntry
    val text = item.text ?: return clipEntry
    val restored = selection.originalIfChanged(text) ?: return clipEntry
    return ClipEntry(ClipData(clip.description, ClipData.Item(restored)))
}
