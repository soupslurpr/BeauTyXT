/* Verifies that a short print sheet leaves editable fields visible above the keyboard. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exercises real landscape IME resizing at normal and enlarged text sizes. */
internal fun Instrumentation.verifyCompactPrintSetup() {
    val originalFlags = uiAutomation.serviceInfo.flags
    val originalRotation = Settings.System.getInt(
        targetContext.contentResolver,
        Settings.System.USER_ROTATION,
        0
    )
    val automaticRotation = Settings.System.getInt(
        targetContext.contentResolver,
        Settings.System.ACCELEROMETER_ROTATION,
        1
    ) != 0
    uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    try {
        check(uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90))
        // Run this phase in a fresh instrumentation process for each system font setting.
        // A local Compose density override does not reach the sheet's separate window.
        val fontScale = Settings.System.getFloat(
            targetContext.contentResolver,
            Settings.System.FONT_SCALE,
            1f
        )
        verifyCompactPrintSetupAtScale(fontScale)
    } finally {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = originalFlags }
        check(uiAutomation.setRotation(originalRotation))
        if (automaticRotation) check(uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE))
    }
}

private fun Instrumentation.verifyCompactPrintSetupAtScale(fontScale: Float) {
    val session = EditorSession(
        title = "Print layout.txt",
        state = EditorDocumentState(RustDocument.createEmpty())
    )
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    val density = activity.resources.displayMetrics.density
    try {
        check(activity.resources.configuration.fontScale == fontScale) {
            "print fixture did not receive Android's font scale $fontScale"
        }
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    DocumentEditor(session, activity::finish, closesDocumentTask = false)
                }
            }
            session.openInitialEditor()
        }
        awaitPrintLayoutCondition { session.canStartPrint }
        waitForEditField()
        requireActionableContentDescription("Send and export").performRequiredClick()
        revealScrollableAction("Print or PDF").performRequiredClick()
        awaitPrintLayoutCondition { session.printSetupDraft != null }
        // A new modal can expose semantics while its entering animation still moves the viewport.
        SystemClock.sleep(500L)
        waitForAccessibilityIdle()
        for ((label, value) in listOf("Top" to "0.75", "Text size" to "14")) {
            val field = revealScrollableNode("print $label at scale $fontScale") { root ->
                root.findPrintField(label)
            }
            field.performRequiredClick()
            awaitPrintLayoutCondition {
                uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            // IME accessibility appears before its animated window has reached its final bounds.
            SystemClock.sleep(750L)
            waitForAccessibilityIdle()
            val visibleField = waitForAccessibilityNode("focused print $label above IME") { node ->
                node.isEditable && node.isFocused && node.isVisibleToUser
            }
            val bounds = Rect().also(visibleField::getBoundsInScreen)
            val keyboard = uiAutomation.windows.first {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
            val keyboardBounds = Rect().also(keyboard::getBoundsInScreen)
            check(bounds.height() >= 48 * density - 1 && bounds.bottom <= keyboardBounds.top) {
                "print $label is clipped at scale $fontScale: $bounds, IME $keyboardBounds"
            }
            check(
                visibleField.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            value
                        )
                    }
                )
            ) { "print $label rejected input" }
            awaitPrintLayoutCondition {
                val draft = session.printSetupDraft
                if (label == "Top") draft?.topMargin == value else draft?.fontSize == value
            }
            SystemClock.sleep(150L)
            waitForAccessibilityIdle()
            capturePrintLayout("$fontScale-$label")
            check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitPrintLayoutCondition {
                uiAutomation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            check(session.printSetupDraft != null) { "keyboard dismissal closed print setup" }
        }
        val printAction = revealScrollableAction("Open print screen")
        check(Rect().also(printAction::getBoundsInScreen).height() >= 48 * density - 1) {
            "print action is clipped at scale $fontScale"
        }
        capturePrintLayout("$fontScale-actions")
        revealScrollableAction("Cancel").performRequiredClick()
        awaitPrintLayoutCondition { session.printSetupDraft == null }
        check(!session.state.hasDocumentChanges) { "print setup changed the source" }
    } catch (failure: Throwable) {
        runCatching { capturePrintLayout("$fontScale-failure") }
        val nodes = StringBuilder()
        uiAutomation.rootInActiveWindow?.findNode { node ->
            nodes.appendLine(node.toString())
            false
        }
        File(
            targetContext.cacheDir,
            "print-setup-layout/$fontScale-failure.txt"
        ).writeText(nodes.toString())
        throw failure
    } finally {
        runOnMainSync {
            activity.finishAndRemoveTask()
            session.close()
        }
        waitForAccessibilityIdle()
    }
}

/** Labels may be exposed as a field hint or a merged label child across platform versions. */
private fun AccessibilityNodeInfo.findPrintField(label: String): AccessibilityNodeInfo? =
    findNode { node ->
        node.isEditable && node.findNode { child ->
            child.hintText?.contains(label) == true || child.text?.contains(label) == true ||
                child.contentDescription?.contains(label) == true
        } != null
    }

private fun Instrumentation.capturePrintLayout(name: String) {
    val directory = File(targetContext.cacheDir, "print-setup-layout")
    check(directory.isDirectory || directory.mkdirs())
    val bitmap = checkNotNull(uiAutomation.takeScreenshot())
    try {
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    } finally {
        bitmap.recycle()
    }
}

private fun awaitPrintLayoutCondition(condition: () -> Boolean) = runBlocking {
    withContext(Dispatchers.Main) {
        withTimeout(10_000L) { while (!condition()) delay(16L) }
    }
}
