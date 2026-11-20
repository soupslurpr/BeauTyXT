/* Exercises one appearance draft through real Activity recreation and IME layout. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicReference
import dev.soupslurpr.beautyxt.BeauTyXTApplication
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.ui.editor.AppearanceGroup
import dev.soupslurpr.beautyxt.ui.editor.AppearanceOption
import dev.soupslurpr.beautyxt.ui.editor.TextAppearanceStoreState
import dev.soupslurpr.beautyxt.ui.editor.TextAppearance

/** The landscape window must have 840 dp available after horizontal safe-drawing insets. */
internal fun Instrumentation.verifyTextAppearanceAdaptation() {
    val app = targetContext.applicationContext as BeauTyXTApplication
    val store = app.textAppearance
    awaitReadingCondition("appearance store not ready") { store.state.value is TextAppearanceStoreState.Ready }
    fun preferences() = (store.state.value as TextAppearanceStoreState.Ready).preferences
    val original = preferences()
    val flags = uiAutomation.serviceInfo.flags
    val rotation = captureFixtureRotation()
    var primaryFailure: Throwable? = null
    val currentActivity = AtomicReference<Activity?>()
    val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { currentActivity.set(activity) }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    fun enter(value: String) {
        val field = waitForAccessibilityNode("appearance draft") { it.isEditable && it.isFocused }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        waitForAccessibilityNode("entered appearance draft") { it.isEditable && it.text.toString() == value }
    }
    fun lineSpacing() = preferences().source.value(AppearanceOption.LineSpacing, AppearanceGroup.Source)
    fun awaitAppearanceHost(modal: Boolean) {
        val expected = if (modal) "a dismissible portrait sheet" else "a landscape pane beside the source field (840 dp after insets)"
        awaitReadingCondition("appearance controls did not occupy $expected") {
            uiAutomation.clearCache()
            val documentWindow = currentActivity.get()?.window?.decorView?.createAccessibilityNodeInfo()?.windowId
                ?: return@awaitReadingCondition false
            uiAutomation.windows.any { window ->
                val root = window.root ?: return@any false
                val title = root.findNode { it.isVisibleToUser && it.text?.toString() == "Text appearance" }
                    ?: return@any false
                if (modal) {
                    window.id != documentWindow && root.findNode { node ->
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
                    } != null
                } else {
                    val field = root.findNode { it.isEditable && it.isVisibleToUser } ?: return@any false
                    val source = Rect().also(field::getBoundsInScreen)
                    val heading = Rect().also(title::getBoundsInScreen)
                    window.id == documentWindow && !source.isEmpty && !heading.isEmpty &&
                        (source.right <= heading.left || heading.right <= source.left)
                }
            }
        }
    }
    try {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        runOnMainSync {
            app.registerActivityLifecycleCallbacks(lifecycle)
            store.update(AppearanceGroup.Source, TextAppearance())
        }
        startActivitySync(Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        check(uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0))
        awaitReadingCondition("portrait fixture did not settle") {
            currentActivity.get()?.resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        requireActionableText("New document").performRequiredClick()
        requireActionableContentDescription("More options").performRequiredClick()
        requireActionableText("Text appearance").performRequiredClick()
        awaitAppearanceHost(modal = true)
        revealScrollableNode("Spacing section") { root ->
            root.findNode { it.text?.toString() == "Spacing" }?.enabledClickableAncestor()
        }.performRequiredClick()
        revealScrollableNode("Line spacing editor") { root ->
            root.findNode { it.contentDescription?.toString() == "Edit Line spacing" }?.enabledClickableAncestor()
        }.performRequiredClick()
        enter("2.2")
        val portraitActivity = currentActivity.get()
        check(uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90))
        awaitReadingCondition("activity did not recreate in landscape") {
            currentActivity.get() != portraitActivity &&
                currentActivity.get()?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        waitForAccessibilityNode("draft survived sheet-to-pane transition") {
            it.isEditable && it.isFocused && it.text.toString() == "2.2"
        }
        check(lineSpacing() == 1.5f) { "recreation applied an unfinished draft" }
        awaitReadingCondition("numeric keyboard did not appear") {
            uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        // Accessibility exposes the IME before both windows finish moving. Wait for
        // stable, unobscured bounds instead of assuming a fixed animation duration.
        var previousBounds: Pair<Rect, Rect>? = null
        var stableSince = SystemClock.uptimeMillis()
        awaitReadingCondition("numeric editor did not settle above the keyboard") {
            uiAutomation.clearCache()
            val keyboard = uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?: return@awaitReadingCondition false
            val field = uiAutomation.rootInActiveWindow?.findNode { it.isEditable && it.isFocused }
                ?: return@awaitReadingCondition false
            val bounds = Rect().also(field::getBoundsInScreen) to Rect().also(keyboard::getBoundsInScreen)
            if (bounds != previousBounds) {
                previousBounds = bounds
                stableSince = SystemClock.uptimeMillis()
            }
            bounds.second.height() > 0 && bounds.first.bottom <= bounds.second.top &&
                SystemClock.uptimeMillis() - stableSince >= 300L
        }
        waitForAccessibilityIdle()
        val keyboardBounds = Rect().also(uiAutomation.windows.first {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }::getBoundsInScreen)
        val density = currentActivity.get()!!.resources.displayMetrics.density
        val numberField = waitForAccessibilityNode("landscape numeric field") { it.isEditable && it.isFocused }
        val fieldBounds = Rect().also(numberField::getBoundsInScreen)
        check(fieldBounds.top >= 0 && fieldBounds.bottom <= keyboardBounds.top &&
            fieldBounds.height() >= 48 * density - 1 && fieldBounds.width() >= 160 * density) {
            "numeric field is clipped or too narrow: $fieldBounds, keyboard=$keyboardBounds"
        }
        for (label in listOf("Cancel", "Apply")) {
            val bounds = Rect().also(requireActionableText(label)::getBoundsInScreen)
            check(bounds.top >= 0 && bounds.bottom <= keyboardBounds.top && bounds.height() >= 48 * density - 1) {
                "$label is obscured or clipped: $bounds, keyboard=$keyboardBounds"
            }
        }
        requireActionableText("Apply").performRequiredClick()
        awaitReadingCondition("retained draft did not apply") { lineSpacing() == 2.2f }
        awaitReadingCondition("keyboard did not close after applying the draft") {
            uiAutomation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        awaitAppearanceHost(modal = false)
        waitForAccessibilityIdle()
        var edit: AccessibilityNodeInfo? = null
        repeat(8) {
            if (edit == null) {
                uiAutomation.clearCache()
                val root = checkNotNull(uiAutomation.windows.firstNotNullOfOrNull { window ->
                    window.root?.takeIf { candidate -> candidate.findNode {
                        it.contentDescription?.toString() == "Close"
                    } != null }
                }) { "appearance panel window was missing after Apply" }
                edit = root.findNode { it.contentDescription?.toString() == "Edit Line spacing" }?.enabledClickableAncestor()
                if (edit == null) {
                    val heading = checkNotNull(root.findNode { it.text?.toString() == "Text appearance" })
                    val headingX = Rect().also(heading::getBoundsInScreen).centerX()
                    // The source editor also scrolls. Locate the list beneath the panel heading.
                    val pane = checkNotNull(root.findNode { node ->
                        val bounds = Rect().also(node::getBoundsInScreen)
                        node.isScrollable && node.isVisibleToUser && headingX in bounds.left until bounds.right
                    }) { "appearance supporting pane was not scrollable" }
                    check(pane.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    SystemClock.sleep(300L)
                    waitForAccessibilityIdle()
                }
            }
        }
        checkNotNull(edit) { "retained Spacing controls were unreachable in landscape" }.performRequiredClick()
        enter("2.3")
        val landscapeActivity = currentActivity.get()
        check(uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0))
        awaitReadingCondition("activity did not recreate in portrait") {
            currentActivity.get() != landscapeActivity &&
                currentActivity.get()?.resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        waitForAccessibilityNode("draft survived pane-to-sheet transition") {
            it.isEditable && it.isFocused && it.text.toString() == "2.3"
        }
        requireActionableText("Cancel").performRequiredClick()
        check(lineSpacing() == 2.2f) { "Cancel committed the retained draft" }
        awaitAppearanceHost(modal = true)
        revealScrollableNode("retained Spacing section") { root ->
            root.findNode { it.contentDescription?.toString() == "Edit Line spacing" }?.enabledClickableAncestor()
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        runFixtureCleanup(primaryFailure,
            { runOnMainSync { currentActivity.get()?.finishAndRemoveTask() } },
            { runOnMainSync { app.unregisterActivityLifecycleCallbacks(lifecycle) } },
            {
                store.update(AppearanceGroup.Source, original.source)
                store.update(AppearanceGroup.Markdown, original.markdown)
                awaitReadingCondition("appearance preferences were not restored") {
                    runCatching {
                        java.io.File(targetContext.noBackupFilesDir, "text-appearance.properties").bufferedReader().use {
                            dev.soupslurpr.beautyxt.ui.editor.readDocumentAppearances(it)
                        } == original
                    }.getOrDefault(false)
                }
            },
            { uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { this.flags = flags } },
            { restoreFixtureRotation(rotation) })
    }
}
