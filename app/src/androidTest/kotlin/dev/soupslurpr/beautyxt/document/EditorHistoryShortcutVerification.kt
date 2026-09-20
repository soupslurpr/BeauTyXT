/* Verifies history-key ownership using Android's real key and modifier decoding. */
package dev.soupslurpr.beautyxt.document

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import dev.soupslurpr.beautyxt.ui.editor.handleEditorHistoryShortcut

/** Verifies rejected history commands never fall through to a field's separate journal. */
internal fun verifyEditorHistoryShortcutOwnership() {
    var undoCalls = 0
    var redoCalls = 0
    val rejectUndo = {
        undoCalls += 1
        false
    }
    val rejectRedo = {
        redoCalls += 1
        false
    }
    for (modifier in intArrayOf(AndroidKeyEvent.META_CTRL_ON, AndroidKeyEvent.META_META_ON)) {
        check(
            handleEditorHistoryShortcut(
                historyKeyEvent(AndroidKeyEvent.KEYCODE_Z, modifier),
                rejectUndo,
                rejectRedo
            )
        ) { "rejected Undo fell through to the text field" }
        check(
            handleEditorHistoryShortcut(
                historyKeyEvent(
                    AndroidKeyEvent.KEYCODE_Z,
                    modifier or AndroidKeyEvent.META_SHIFT_ON
                ),
                rejectUndo,
                rejectRedo
            )
        ) { "rejected Shift-Z Redo fell through to the text field" }
        check(
            handleEditorHistoryShortcut(
                historyKeyEvent(AndroidKeyEvent.KEYCODE_Y, modifier),
                rejectUndo,
                rejectRedo
            )
        ) { "rejected Y Redo fell through to the text field" }
    }
    check(undoCalls == 2 && redoCalls == 4) { "history shortcuts dispatched incorrect actions" }
    val unrelated = listOf(
        historyKeyEvent(AndroidKeyEvent.KEYCODE_Z, 0),
        historyKeyEvent(AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.META_CTRL_ON),
        historyKeyEvent(
            AndroidKeyEvent.KEYCODE_Z,
            AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_ALT_ON
        ),
        historyKeyEvent(
            AndroidKeyEvent.KEYCODE_Z,
            AndroidKeyEvent.META_CTRL_ON,
            action = AndroidKeyEvent.ACTION_UP
        )
    )
    unrelated.forEach { event ->
        check(!handleEditorHistoryShortcut(event, rejectUndo, rejectRedo)) {
            "history handler consumed an unrelated key event"
        }
    }
    check(undoCalls == 2 && redoCalls == 4) { "unrelated keys dispatched history actions" }
}

/** Creates one Android key event without injecting input into another application. */
private fun historyKeyEvent(
    keyCode: Int,
    modifiers: Int,
    action: Int = AndroidKeyEvent.ACTION_DOWN
): KeyEvent = KeyEvent(AndroidKeyEvent(0, 0, action, keyCode, 0, modifiers))
