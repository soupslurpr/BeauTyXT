package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import dev.soupslurpr.beautyxt.ui.keyboardPrivacyRequest

/** Checks the unchanged connection/metadata and the real document host, not just a flag helper. */
internal fun Instrumentation.verifyKeyboardPrivacy() {
    runOnMainSync {
        val connection = BaseInputConnection(View(targetContext), false)
        var calls = 0
        val request = keyboardPrivacyRequest(
            PlatformTextInputMethodRequest { info ->
                calls++
                info.inputType = InputType.TYPE_CLASS_TEXT
                info.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN
                info.initialSelStart = 3
                info.initialSelEnd = 5
                connection
            }
        )
        repeat(2) {
            val info = EditorInfo()
            check(request.createInputConnection(info) === connection)
            check(info.inputType == InputType.TYPE_CLASS_TEXT)
            val expectedOptions = EditorInfo.IME_ACTION_SEARCH or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            check(info.imeOptions == expectedOptions)
            check(info.initialSelStart == 3 && info.initialSelEnd == 5)
        }
        check(calls == 2)
        connection.closeConnection()
    }
    val activity = startHomeDestination("New document")
    try {
        fun input(action: (InputConnection) -> Unit) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var result: Result<Unit>? = null
            while (result == null && SystemClock.uptimeMillis() < deadline) {
                runOnMainSync {
                    val view = activity.window.decorView.findTextEditorView()
                    val info = EditorInfo()
                    val connection = view?.onCreateInputConnection(info)
                    if (connection != null) {
                        result = runCatching {
                            check(
                                info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING !=
                                    0
                            )
                            action(connection)
                        }
                    }
                }
                if (result == null) SystemClock.sleep(20)
            }
            checkNotNull(result) { "the document host never acquired a text-input connection" }
                .getOrThrow()
            waitForAccessibilityIdle()
        }
        waitForEditField().performRequiredClick()
        waitForAccessibilityIdle()
        input {
            check(it.setComposingText("private example", 1))
            check(it.finishComposingText())
        }
        waitForEditorText("private example")
        requireActionableContentDescription("Undo").performRequiredClick()
        waitForEditorText("")
        requireActionableContentDescription("Redo").performRequiredClick()
        waitForEditorText("private example")
        requireActionableContentDescription("Find in document").performRequiredClick()
        waitForAccessibilityIdle()
        input { check(it.setComposingText("private", 1)) }
        waitForAccessibilityNode("private-keyboard Find matches composing input") {
            it.text?.toString()?.endsWith("Match on line 1") == true
        }
        requireActionableContentDescription("Close Find").performRequiredClick()
        waitForEditorText("private example")
    } finally {
        runOnMainSync { activity.finishAndRemoveTask() }
        waitForAccessibilityIdle()
    }
}
