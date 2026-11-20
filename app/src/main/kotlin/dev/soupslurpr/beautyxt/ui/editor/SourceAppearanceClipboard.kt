/* Keeps native source Copy/Cut exact when tabs have a different visual advance. */
package dev.soupslurpr.beautyxt.ui.editor

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.TextRange

/**
 * Compose copies its output text, including output-transformed characters. Record the source
 * selection while producing that output, before Copy collapses it or Cut removes it. Keeping
 * the last nonempty selection also covers repeated hardware Copy without a new layout.
 * This object belongs to one bounded field, never to another document or the whole app.
 */
internal class SourceClipboardSelection {
    @Volatile private var source: String? = null

    fun capture(text: CharSequence, selection: TextRange) {
        if (!selection.collapsed) source = text.subSequence(selection.min, selection.max).toString()
    }

    fun original(display: CharSequence): CharSequence {
        val selected = source ?: return display
        return if (sourcePresentationMatches(display, selected)) selected else display
    }
}

/** Wraps writes from this field only; paste and platform clipboard capabilities are unchanged. */
internal class SourceAppearanceClipboard(
    private val delegate: Clipboard,
    private val selection: SourceClipboardSelection
) : Clipboard by delegate {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val clip = clipEntry?.clipData
        val text = clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
        if (text == null) {
            delegate.setClipEntry(clipEntry)
        } else {
            delegate.setClipEntry(ClipEntry(ClipData(clip.description, ClipData.Item(selection.original(text)))))
        }
    }
}
