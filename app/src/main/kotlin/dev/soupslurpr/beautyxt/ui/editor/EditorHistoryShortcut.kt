/* Routes hardware-keyboard history commands through the document session. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** Consumes recognized history commands even when the document rejects them. */
internal fun handleEditorHistoryShortcut(
    event: KeyEvent,
    onUndo: () -> Boolean,
    onRedo: () -> Boolean
): Boolean {
    if (
        event.type != KeyEventType.KeyDown ||
        event.isAltPressed ||
        (!event.isCtrlPressed && !event.isMetaPressed)
    ) {
        return false
    }
    when {
        event.key == Key.Z && event.isShiftPressed -> onRedo()
        event.key == Key.Z -> onUndo()
        event.key == Key.Y -> onRedo()
        else -> return false
    }
    // Keep rejected commands out of Compose's independent field history.
    return true
}
