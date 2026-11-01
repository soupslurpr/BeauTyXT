@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.FindInputFocus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.runBlocking

/** Forces the observed IME ordering through each real field and its live input connection. */
internal fun Instrumentation.verifyFindInputOwnership() {
    val original = "cat dog cat"
    for (target in listOf(FindInputFocus.Query, FindInputFocus.Replacement)) {
        withReadingPage(original, initialPresentation = EditorPresentation.Text) { activity, session ->
            val liveInput = AtomicReference<LiveFindInputConnection?>()
            val state = if (target == FindInputFocus.Query) session.findFieldState else session.replacementFieldState
            val base = if (target == FindInputFocus.Query) "cat" else "dog"
            var inputReadiness = "not checked"
            fun awaitFrames() = runBlocking { repeat(2) { awaitFrame() } }
            fun matchingLiveField(text: String): Pair<LiveFindInputConnection, FindInputFieldIdentity>? {
                inputReadiness = "expected=$text; windowFocus=${activity.window.decorView.hasWindowFocus()}; " +
                    "modelFocus=${session.findInputFocus}; text=${state.text}; selection=${state.selection}; " +
                    "composition=${state.composition}; connectionPresent=${liveInput.get() != null}; " +
                    "connectionClosed=${liveInput.get()?.closed?.get()}"
                if (!activity.window.decorView.hasWindowFocus() || session.findInputFocus != target) return null
                val fields = findInputFieldIdentities(activity.window.decorView, text, state.selection)
                inputReadiness += "; matchingFields=${fields.size}"
                val field = fields.singleOrNull() ?: return null
                val input = liveInput.get()?.takeUnless { it.closed.get() } ?: return null
                val extracted = input.connection.getExtractedText(ExtractedTextRequest(), 0) ?: return null
                inputReadiness += "; extracted=${extracted.text}; inputSelection=" +
                    "${extracted.selectionStart}:${extracted.selectionEnd}"
                if (extracted.text.toString() != text || extracted.selectionStart != state.selection.start ||
                    extracted.selectionEnd != state.selection.end) return null
                return input to field
            }
            fun awaitLiveField(text: String) {
                try {
                    awaitReadingCondition("$target input connection did not match its focused field") {
                        matchingLiveField(text) != null
                    }
                } catch (failure: AssertionError) {
                    throw AssertionError("${failure.message}; $inputReadiness", failure)
                }
            }
            fun historyKey(modifiers: Int): KeyEvent {
                val now = SystemClock.uptimeMillis()
                return KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, modifiers,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            }
            runOnMainSync {
                activity.setContent {
                    BeauTyXTTheme {
                        val interceptor = remember(activity, session) {
                            PlatformTextInputInterceptor { request, next ->
                                next.startInputMethod(PlatformTextInputMethodRequest { attributes ->
                                    // Preserve the exact factory call and EditorInfo of the real field.
                                    val connection = request.createInputConnection(attributes)
                                    val input = LiveFindInputConnection(connection)
                                    liveInput.set(input)
                                    object : InputConnectionWrapper(connection, false) {
                                        override fun closeConnection() {
                                            input.closed.set(true)
                                            super.closeConnection()
                                        }
                                    }
                                })
                            }
                        }
                        InterceptPlatformTextInput(interceptor) {
                            DocumentEditor(session, activity::finish, closesDocumentTask = false)
                        }
                    }
                }
                // This control requires software input; the unchanged keyboard journey covers
                // Find opened without requesting an IME session.
                check(session.showFind(true))
                session.showReplace(true)
                session.updateFindFieldValue(TextFieldValue("cat", TextRange(3)))
                session.updateReplacementFieldValue(TextFieldValue("dog", TextRange(3)))
                if (target == FindInputFocus.Query) check(session.showFind(true))
            }
            awaitReadingCondition("$target did not focus its real input field") {
                hasComposeKeyboardFocus(activity.window.decorView, editableText = base)
            }
            // Activate input and select through the real control before the single input edit.
            val field = waitForAccessibilityNode("$target input") {
                it.isEditable && it.isFocused && it.text?.toString() == base
            }
            check(field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, base.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, base.length)
                }))
            awaitFrames()
            awaitLiveField(base)
            val revision = session.state.metrics!!.revision
            var identity: FindInputFieldIdentity? = null
            runOnMainSync {
                val (_, focusedField) = checkNotNull(matchingLiveField(base))
                identity = focusedField
            }
            // Use the hardware edit from the observed failure. A direct commitText can
            // legitimately replace an IME's existing composing word instead of appending.
            sendStringSync("x")
            awaitReadingCondition("$target did not accept the hardware input edit") {
                state.text.toString() == base + "x"
            }
            awaitFrames()
            awaitLiveField(base + "x")
            runOnMainSync {
                val (input, focusedField) = checkNotNull(matchingLiveField(base + "x"))
                check(focusedField == identity) { "$target changed field identity after typing" }
                val undo = historyKey(KeyEvent.META_CTRL_ON)
                check(activity.dispatchKeyEvent(undo)) { "$target did not handle local Undo" }
                check(state.text.toString() == base) { "$target local Undo did not restore its text" }
                // No frame or recomposition can run between Undo and the stale-length IME command.
                val accepted = input.connection.setComposingRegion(0, base.length + 1)
                Log.i("BeauTyXTFindInputOwnership", "$target Undo/composition; accepted=$accepted; " +
                    "sameConnection=${liveInput.get() === input}; closed=${input.closed.get()}; " +
                    "text=${state.text}; selection=${state.selection}; composition=${state.composition}; " +
                    "canRedo=${state.undoState.canRedo}")
                check(state.text.toString() == base) {
                    "$target composition-only input restored text that Undo removed"
                }
                if (accepted && liveInput.get() === input && !input.closed.get()) {
                    check(state.composition == TextRange(0, base.length)) {
                        "$target accepted an incorrect composing range"
                    }
                }
                check(state.undoState.canRedo) { "$target composition-only input discarded Redo" }
                activity.dispatchKeyEvent(KeyEvent.changeAction(undo, KeyEvent.ACTION_UP))
                val redo = historyKey(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                check(activity.dispatchKeyEvent(redo)) { "$target did not handle local Redo" }
                activity.dispatchKeyEvent(KeyEvent.changeAction(redo, KeyEvent.ACTION_UP))
                check(state.text.toString() == base + "x") { "$target Redo did not restore the input edit" }
                check(session.state.metrics!!.revision == revision &&
                    session.activeDraft!!.textFieldState.text.toString() == original) {
                    "$target local input history changed the document"
                }
            }
            awaitFrames()
            runOnMainSync {
                check(state.text.toString() == base + "x") { "$target Redo text did not remain stable" }
                check(session.state.metrics!!.revision == revision &&
                    session.activeDraft!!.textFieldState.text.toString() == original)
            }
        }
    }
}

private class LiveFindInputConnection(val connection: InputConnection) {
    val closed = AtomicBoolean(false)
}

private data class FindInputFieldIdentity(val root: View, val nodeId: Int)

private fun findInputFieldIdentities(view: View, text: String, selection: TextRange): List<FindInputFieldIdentity> =
    buildList {
        if (view is ViewRootForTest) {
            for (node in view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)) {
                if (node.config.getOrNull(SemanticsProperties.Focused) == true &&
                    node.config.getOrNull(SemanticsProperties.EditableText)?.text == text &&
                    node.config.getOrNull(SemanticsProperties.TextSelectionRange) == selection) {
                    add(FindInputFieldIdentity(view, node.id))
                }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) addAll(findInputFieldIdentities(view.getChildAt(index), text, selection))
        }
    }
