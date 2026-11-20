/* Exercises live preferences through the real editor, clipboard, and durable store. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.AndroidUiDispatcher
import dev.soupslurpr.beautyxt.BeauTyXTApplication
import dev.soupslurpr.beautyxt.ui.editor.AppearanceGroup
import dev.soupslurpr.beautyxt.ui.editor.AppearanceOption
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus
import dev.soupslurpr.beautyxt.ui.editor.TextAppearanceStoreState
import dev.soupslurpr.beautyxt.ui.editor.TextAppearanceStore
import dev.soupslurpr.beautyxt.ui.editor.TextAppearance
import dev.soupslurpr.beautyxt.ui.editor.readDocumentAppearances
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal fun Instrumentation.verifyTextAppearance() {
    verifyMarkdownEmphasis()
    val store = (targetContext.applicationContext as BeauTyXTApplication).textAppearance
    awaitReadingCondition("appearance preferences did not load") { store.state.value is TextAppearanceStoreState.Ready }
    fun preferences() = (store.state.value as TextAppearanceStoreState.Ready).preferences
    val original = preferences()
    val originalServiceFlags = uiAutomation.serviceInfo.flags
    uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    fun menuChoice(label: String): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        awaitReadingCondition("appearance menu choice '$label' was not accessible") {
            uiAutomation.clearCache()
            // A popup above a modal sheet can differ from rootInActiveWindow.
            match = uiAutomation.windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { window ->
                window.root?.findNode { it.text?.toString() == label && it.isCheckable }
            }
            match != null
        }
        return checkNotNull(match)
    }
    fun openSizeEditor(value: String): AccessibilityNodeInfo {
        // Store updates precede recomposition; wait for the button's announced value.
        val control = waitForAccessibilityNode("displayed text size $value") {
            it.contentDescription?.toString() == "Edit Text size" &&
                it.enabledClickableAncestor()?.stateDescription?.toString() == "$value sp"
        }
        checkNotNull(control.enabledClickableAncestor()).performRequiredClick()
        return waitForAccessibilityNode("selected exact size $value") {
            it.isEditable && it.isFocused && it.text?.toString() == value &&
                it.textSelectionStart == 0 && it.textSelectionEnd == value.length
        }
    }
    fun typeInNumberDialog(value: String) {
        // Field focus can be exposed before a newly opened dialog receives key events.
        awaitReadingCondition("numeric dialog did not receive window focus") {
            uiAutomation.clearCache()
            uiAutomation.windows.any { window ->
                window.isFocused && window.root?.findNode { it.isEditable && it.isFocused } != null
            }
        }
        waitForAccessibilityIdle()
        sendStringSync(value)
    }
    val text = "# Reading comfort\n\nCafé 😀 with **strong words** and العربية.\n\n```\n\tfirst\tsecond\n```\n" +
        "A long source line ".repeat(80)
    try {
        verifyMarkdownAppearanceRoles(store)
        verifyLargeMarkdownListMarkers(store)
        runOnMainSync {
            store.update(AppearanceGroup.Source, TextAppearance())
            store.update(AppearanceGroup.Markdown, TextAppearance())
        }
        for (family in 0..2) {
            runOnMainSync {
                store.update(AppearanceGroup.Markdown, TextAppearance()
                    .change(AppearanceOption.Weight, 700f).change(AppearanceOption.Font, family.toFloat()))
                store.update(AppearanceGroup.Source, TextAppearance()
                    .change(AppearanceOption.Weight, 700f).change(AppearanceOption.Font, family.toFloat()))
            }
            check(preferences().markdown.value(AppearanceOption.Weight, AppearanceGroup.Markdown) == 700f)
            check(preferences().source.value(AppearanceOption.Weight, AppearanceGroup.Source) == 700f)
        }
        runOnMainSync {
            store.update(AppearanceGroup.Source, TextAppearance())
            store.update(AppearanceGroup.Markdown, TextAppearance())
        }
        withReadingPage(text) { activity, session ->
            val revision = checkNotNull(session.state.metrics).revision
            requireActionableContentDescription("More options").performRequiredClick()
            requireActionableText("Text appearance").performRequiredClick()
            // Selection menus expose the current choice, retain focus, and follow a changed group.
            requireActionableContentDescription("Choose appearance group").performRequiredClick()
            check(menuChoice("Rendered Markdown").checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE)
            check(menuChoice("Plain text & source").checked == AccessibilityNodeInfo.CHECKED_STATE_FALSE)
            menuChoice("Plain text & source").performRequiredClick()
            requireActionableContentDescription("Choose appearance group").performRequiredClick()
            check(menuChoice("Plain text & source").checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE)
            menuChoice("Rendered Markdown").performRequiredClick()
            fun appearancePanelWindow(): AccessibilityNodeInfo? {
                uiAutomation.clearCache()
                return uiAutomation.windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { window ->
                    window.root?.takeIf { it.findNode { node -> node.viewIdResourceName == "appearance_panel" } != null }
                }
            }
            fun appearancePanelRoot(): AccessibilityNodeInfo? =
                appearancePanelWindow()?.findNode { it.viewIdResourceName == "appearance_panel" }
            fun scrollAppearanceControlsByFraction(list: AccessibilityNodeInfo, forward: Boolean) {
                val visible = Rect().also(list::getBoundsInScreen)
                val viewport = list.window?.let { Rect().also(it::getBoundsInScreen) }
                    ?: Rect(0, 0, activity.window.decorView.width, activity.window.decorView.height)
                if (!visible.intersect(viewport) || visible.height() <= 32) return
                val x = visible.centerX().toFloat()
                val startY = if (forward) visible.top + visible.height() * 0.75f else visible.top + visible.height() * 0.25f
                val endY = if (forward) visible.top + visible.height() * 0.35f else visible.top + visible.height() * 0.65f
                val downTime = SystemClock.uptimeMillis()
                fun dispatch(action: Int, y: Float, eventTime: Long) {
                    val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    try {
                        uiAutomation.injectInputEvent(event, true)
                    } finally {
                        event.recycle()
                    }
                }
                dispatch(MotionEvent.ACTION_DOWN, startY, downTime)
                val steps = 12
                for (step in 1..steps) {
                    SystemClock.sleep(16)
                    val y = startY + (endY - startY) * step / steps
                    dispatch(MotionEvent.ACTION_MOVE, y, SystemClock.uptimeMillis())
                }
                // Pause before lifting so Compose treats the gesture as a settled drag with zero fling velocity.
                SystemClock.sleep(100)
                dispatch(MotionEvent.ACTION_UP, endY, SystemClock.uptimeMillis())
                SystemClock.sleep(150)
                waitForAccessibilityIdle()
            }
            fun revealInAppearanceList(description: String, find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
                awaitReadingCondition("appearance panel not available before revealing $description") {
                    appearancePanelRoot() != null
                }
                appearancePanelRoot()?.let(find)?.let { return it }
                // Expanding a partially expanded modal sheet first keeps touch drags inside LazyColumn instead of snapping the sheet.
                appearancePanelWindow()?.findNode { node ->
                    node.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_EXPAND }
                }?.let { expandHandle ->
                    expandHandle.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                    SystemClock.sleep(400)
                    waitForAccessibilityIdle()
                    appearancePanelRoot()?.let(find)?.let { return it }
                }
                val trace = mutableListOf<String>()
                for ((forward, action) in listOf(
                    true to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    false to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )) {
                    for (attempt in 0 until 24) {
                        val root = appearancePanelRoot() ?: break
                        find(root)?.let { return it }
                        val list = root.findNode { it.viewIdResourceName == "appearance_controls" }
                        if (list == null) {
                            trace += "dir=$forward attempt=$attempt list=null"
                            break
                        }
                        val hasAction = list.actionList.any { it.id == action }
                        if (!hasAction) {
                            trace += "dir=$forward attempt=$attempt noAction=${action}"
                            break
                        }
                        if (attempt == 0) {
                            val lb = Rect().also(list::getBoundsInScreen)
                            val wb = list.window?.let { Rect().also(it::getBoundsInScreen) }
                            trace += "dir=$forward lb=$lb wb=$wb"
                        }
                        scrollAppearanceControlsByFraction(list, forward)
                        appearancePanelRoot()?.let(find)?.let { return it }
                    }
                }
                val texts = mutableListOf<String>()
                fun collect(n: AccessibilityNodeInfo?) {
                    if (n == null) return
                    val t = n.text?.toString()
                    val cd = n.contentDescription?.toString()
                    if (!t.isNullOrEmpty() || !cd.isNullOrEmpty()) {
                        texts += "${t.orEmpty()}|${cd.orEmpty()}(v=${n.isVisibleToUser})"
                    }
                    for (i in 0 until n.childCount) collect(n.getChild(i))
                }
                collect(appearancePanelRoot())
                error("could not reveal $description in appearance controls list; trace=$trace; nodes=${texts.take(30)}")
            }
            fun nodeBounds(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): Rect {
                var bounds: Rect? = null
                awaitReadingCondition("$description was not found in appearance panel") {
                    val node = appearancePanelRoot()?.findNode(predicate) ?: return@awaitReadingCondition false
                    bounds = Rect().also(node::getBoundsInScreen)
                    true
                }
                return checkNotNull(bounds)
            }
            fun fontHeadingHasStatus(root: AccessibilityNodeInfo, expected: String): Boolean {
                val titleNode = root.findNode { it.text?.toString() == "Font" } ?: return false
                val pickerNode = root.findNode { it.contentDescription?.toString() == "Font" } ?: return false
                val titleBounds = Rect().also(titleNode::getBoundsInScreen)
                val pickerBounds = Rect().also(pickerNode::getBoundsInScreen)
                return root.findNode { node ->
                    if (node.text?.toString() != expected) return@findNode false
                    val statusBounds = Rect().also(node::getBoundsInScreen)
                    statusBounds.top >= titleBounds.top && statusBounds.top < pickerBounds.top
                } != null
            }
            // Hold the selected font label constant ("System default") to isolate Default <-> Custom layout shift.
            val defaultFontBounds = nodeBounds("default font control") { it.contentDescription?.toString() == "Font" }
            requireActionableContentDescription("Font").performRequiredClick()
            val selectedFont = menuChoice("System default")
            check(selectedFont.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE && selectedFont.isFocusable)
            check(selectedFont.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            selectedFont.performRequiredClick()
            awaitReadingCondition("selecting System default did not mark Font Custom") {
                val root = appearancePanelRoot() ?: return@awaitReadingCondition false
                preferences().markdown.custom[AppearanceOption.Font] == 0f &&
                    fontHeadingHasStatus(root, "Custom") &&
                    root.findNode { it.contentDescription?.toString() == "Reset Font" } != null
            }
            check(nodeBounds("custom font control") { it.contentDescription?.toString() == "Font" } == defaultFontBounds) {
                "Font control shifted when switching from Default to Custom"
            }
            requireActionableContentDescription("Reset Font").performRequiredClick()
            awaitReadingCondition("Font reset did not restore the default") {
                AppearanceOption.Font !in preferences().markdown.custom
            }
            awaitReadingCondition("Reset Font remained accessible or Font status stayed Custom after restoring Default") {
                val root = appearancePanelRoot() ?: return@awaitReadingCondition false
                fontHeadingHasStatus(root, "Default") &&
                    root.findNode { it.contentDescription?.toString() == "Reset Font" } == null
            }
            check(nodeBounds("reset font control") { it.contentDescription?.toString() == "Font" } == defaultFontBounds) {
                "Font control shifted when switching from Custom to Default"
            }
            // Separately verify choosing a different font updates menu selection and resets cleanly.
            requireActionableContentDescription("Font").performRequiredClick()
            menuChoice("Serif").performRequiredClick()
            requireActionableContentDescription("Font").performRequiredClick()
            check(menuChoice("Serif").checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE)
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            requireActionableContentDescription("Reset Font").performRequiredClick()
            awaitReadingCondition("Serif font reset did not restore the default") {
                AppearanceOption.Font !in preferences().markdown.custom
            }
            if (activity.resources.configuration.screenWidthDp < 840) {
                runOnMainSync {
                    store.update(AppearanceGroup.Markdown, TextAppearance().change(AppearanceOption.Size, 32f))
                }
                verifyAppearanceAccessibleScrolling()
                runOnMainSync { store.update(AppearanceGroup.Markdown, TextAppearance()) }
                waitForAccessibilityNode("default size after scrolling fixture") {
                    it.contentDescription?.toString() == "Edit Text size" &&
                        it.enabledClickableAncestor()?.stateDescription?.toString() == "18 sp"
                }
            }
            val sizeHelpText = "Your system font-size setting also applies."
            val increase = revealInAppearanceList("text size controls and help") { root ->
                val increaseNode = root.findNode { it.contentDescription?.toString() == "Increase Text size" }
                    ?.enabledClickableAncestor()
                val editNode = root.findNode { it.contentDescription?.toString() == "Edit Text size" && it.isVisibleToUser }
                val sliderNode = root.findNode { it.rangeInfo != null && it.text?.toString() == "Text size" && it.isVisibleToUser }
                val helpNode = root.findNode { it.text?.toString() == sizeHelpText && it.isVisibleToUser }
                if (increaseNode != null && editNode != null && sliderNode != null && helpNode != null) increaseNode else null
            }
            val defaultSizeEditBounds = nodeBounds("default text size button") { it.contentDescription?.toString() == "Edit Text size" }
            val defaultSizeHelpBounds = nodeBounds("default text size help") { it.text?.toString() == sizeHelpText }
            increase.performRequiredClick()
            awaitReadingCondition("Markdown size did not update immediately") {
                preferences().markdown.custom[AppearanceOption.Size] == 18.5f
            }
            check(preferences().source.custom.isEmpty())
            // TalkBack must receive the name on the adjustable node, not a separate stop.
            awaitReadingCondition("updated named text size slider was not accessible in settled viewport") {
                val root = appearancePanelRoot() ?: return@awaitReadingCondition false
                root.findNode {
                    it.rangeInfo != null && it.text?.toString() == "Text size" &&
                        it.stateDescription?.toString() == "18.5 sp"
                } != null && root.findNode { it.text?.toString() == "Default: 18 sp" } != null
            }
            check(nodeBounds("custom text size button") { it.contentDescription?.toString() == "Edit Text size" } == defaultSizeEditBounds) {
                "Text size control shifted when switching from Default to Custom"
            }
            check(nodeBounds("custom text size help") { it.text?.toString() == sizeHelpText } == defaultSizeHelpBounds) {
                "Text size help shifted when Default: 18 sp appeared"
            }
            openSizeEditor("18.5")
            typeInNumberDialog("22.5")
            waitForAccessibilityNode("typed draft replaced the selected value") { it.isEditable && it.text.toString() == "22.5" }
            check(preferences().markdown.custom[AppearanceOption.Size] == 18.5f) {
                "typing committed an unconfirmed numeric draft"
            }
            requireActionableText("Apply").performRequiredClick()
            awaitReadingCondition("Apply did not commit the exact value") {
                preferences().markdown.custom[AppearanceOption.Size] == 22.5f
            }
            openSizeEditor("22.5")
            typeInNumberDialog("80")
            fun enterNumber(value: String) {
                val field = waitForAccessibilityNode("appearance number field") { it.isEditable }
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
                waitForAccessibilityIdle()
            }
            val invalid = waitForAccessibilityNode("invalid appearance field") { it.isEditable && it.isContentInvalid }
            check(invalid.text.toString() == "80")
            check(!invalid.error.isNullOrBlank()) { "invalid exact value has no accessible correction" }
            check(preferences().markdown.custom[AppearanceOption.Size] == 22.5f) {
                "an intermediate keystroke from invalid input was committed"
            }
            requireActionableText("Cancel").performRequiredClick()
            check(preferences().markdown.custom[AppearanceOption.Size] == 22.5f)
            openSizeEditor("22.5")
            enterNumber("24")
            requireActionableText("Cancel").performRequiredClick()
            check(preferences().markdown.custom[AppearanceOption.Size] == 22.5f) {
                "Cancel committed a valid numeric draft"
            }
            openSizeEditor("22.5")
            enterNumber("999")
            enterNumber("23.5")
            waitForAccessibilityNode("corrected appearance field") { it.isEditable && !it.isContentInvalid }
            requireActionableText("Apply").performRequiredClick()
            awaitReadingCondition("corrected draft did not apply") {
                preferences().markdown.custom[AppearanceOption.Size] == 23.5f
            }
            requireActionableContentDescription("Reset Text size").performRequiredClick()
            awaitReadingCondition("Size reset did not restore the default") {
                AppearanceOption.Size !in preferences().markdown.custom
            }
            awaitReadingCondition("hidden Text size default reference remained accessible") {
                val root = appearancePanelRoot() ?: return@awaitReadingCondition false
                root.findNode {
                    it.contentDescription?.toString() == "Edit Text size" &&
                        it.enabledClickableAncestor()?.stateDescription?.toString() == "18 sp"
                } != null &&
                    root.findNode { it.contentDescription?.toString() == "Reset Text size" } == null &&
                    root.findNode { it.text?.toString() == "Default: 18 sp" } == null
            }
            check(nodeBounds("reset text size button") { it.contentDescription?.toString() == "Edit Text size" } == defaultSizeEditBounds) {
                "Text size control shifted when switching from Custom to Default"
            }
            check(nodeBounds("reset text size help") { it.text?.toString() == sizeHelpText } == defaultSizeHelpBounds) {
                "Text size help shifted when Default: 18 sp disappeared"
            }
            val defaultField = openSizeEditor("18")
            check(defaultField.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0)
            }))
            waitForAccessibilityIdle()
            check(AppearanceOption.Size !in preferences().markdown.custom) {
                "moving the caret changed Default to Custom"
            }
            check(defaultField.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            awaitReadingCondition("Done did not close the untouched numeric editor") {
                uiAutomation.rootInActiveWindow?.findNode { it.isEditable && it.isFocused } == null
            }
            check(AppearanceOption.Size !in preferences().markdown.custom) {
                "applying an untouched default changed it to Custom"
            }
            requireActionableContentDescription("Close").performRequiredClick()
            requireActionableContentDescription("More options").performRequiredClick()
            requireActionableText("Text appearance").performRequiredClick()
            requireActionableText("Layout").performRequiredClick()
            fun findSwitch(root: AccessibilityNodeInfo, title: String, status: String): AccessibilityNodeInfo? =
                root.findNode { it.contentDescription?.toString() == "$title, $status" }?.enabledClickableAncestor()
            val widthSwitch = revealInAppearanceList("available-width switch") { root ->
                findSwitch(root, "Use available width", "Default")
            }
            check(widthSwitch.checked == AccessibilityNodeInfo.CHECKED_STATE_FALSE)
            widthSwitch.performRequiredClick()
            awaitReadingCondition("tapping the switch row did not enable available width") {
                preferences().markdown.custom[AppearanceOption.FullWidth] == 1f
            }
            awaitReadingCondition("Use available width switch did not announce Custom status without a duplicate footer stop") {
                val root = appearancePanelRoot() ?: return@awaitReadingCondition false
                val updated = findSwitch(root, "Use available width", "Custom")
                updated != null && updated.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE &&
                    root.findNode { it.text?.toString() == "Custom" } == null
            }
            requireActionableContentDescription("Reset Use available width").performRequiredClick()
            awaitReadingCondition("FullWidth reset did not restore the default") {
                AppearanceOption.FullWidth !in preferences().markdown.custom
            }
            requireActionableContentDescription("Choose appearance group").performRequiredClick()
            menuChoice("Plain text & source").performRequiredClick()
            val wrapSwitch = revealInAppearanceList("wrap switch and tab width heading") { root ->
                val switchNode = findSwitch(root, "Wrap long lines", "Default")
                val tabHeading = root.findNode {
                    it.text?.toString() == "Tab width" && it.rangeInfo == null && it.isVisibleToUser
                }
                if (switchNode != null && tabHeading != null) switchNode else null
            }
            val defaultTabHeadingBounds = nodeBounds("tab width heading below wrap switch") {
                it.text?.toString() == "Tab width" && it.rangeInfo == null
            }
            wrapSwitch.performRequiredClick()
            awaitReadingCondition("tapping wrap switch did not disable wrapping") {
                preferences().source.custom[AppearanceOption.Wrap] == 0f
            }
            requireActionableContentDescription("Reset Wrap long lines")
            awaitReadingCondition("Wrap long lines switch did not announce Custom status without a duplicate footer stop") {
                val root = appearancePanelRoot() ?: return@awaitReadingCondition false
                val updated = findSwitch(root, "Wrap long lines", "Custom")
                updated != null && updated.checked == AccessibilityNodeInfo.CHECKED_STATE_FALSE &&
                    root.findNode { it.text?.toString() == "Custom" } == null
            }
            check(nodeBounds("custom tab width heading") {
                it.text?.toString() == "Tab width" && it.rangeInfo == null
            } == defaultTabHeadingBounds) {
                "Switch control shifted layout when switching from Default to Custom"
            }
            requireActionableContentDescription("Reset Wrap long lines").performRequiredClick()
            awaitReadingCondition("Wrap reset did not restore the default") {
                AppearanceOption.Wrap !in preferences().source.custom
            }
            awaitReadingCondition("Reset Wrap long lines remained accessible after restoring Default") {
                val root = appearancePanelRoot() ?: return@awaitReadingCondition false
                val updated = findSwitch(root, "Wrap long lines", "Default")
                updated != null && updated.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE &&
                    root.findNode { it.contentDescription?.toString() == "Reset Wrap long lines" } == null
            }
            check(nodeBounds("reset tab width heading") {
                it.text?.toString() == "Tab width" && it.rangeInfo == null
            } == defaultTabHeadingBounds) {
                "Switch control shifted layout when switching from Custom to Default"
            }
            requireActionableContentDescription("Close").performRequiredClick()
            requireActionableContentDescription("Show source text").performRequiredClick()
            waitForEditField()
            val draft = checkNotNull(session.activeDraft)
            val tab = text.indexOf('\t')
            runOnMainSync {
                draft.textFieldState.edit { selection = TextRange(tab, tab + 6) }
                store.update(AppearanceGroup.Source, TextAppearance()
                    .change(AppearanceOption.Size, 26f)
                    .change(AppearanceOption.WordSpacing, 30f)
                    .change(AppearanceOption.LetterSpacing, 10f)
                    .change(AppearanceOption.TabWidth, 8f)
                    .change(AppearanceOption.Wrap, 0f))
            }
            waitForAccessibilityIdle()
            check(draft.textFieldState.text.toString() == text) { "appearance changed source characters" }
            check(draft.textFieldState.selection == TextRange(tab, tab + 6)) { "appearance changed the edit selection" }
            check(session.state.metrics?.revision == revision) { "appearance created a source edit" }
            check(session.presentation == EditorPresentation.Text)
            val field = waitForEditField()
            check(field.performAction(AccessibilityNodeInfo.ACTION_COPY)) { "styled source selection could not be copied" }
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            awaitReadingCondition("copy did not retain the original source tab") {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text.substring(tab, tab + 6)
            }
            runBlocking {
                check(session.state.readSourceRange(revision, Utf16Range(0, text.length.toLong())) == text)
            }
            runOnMainSync { draft.textFieldState.edit { selection = TextRange(tab, tab + 6) } }
            waitForAccessibilityIdle()
            check(waitForEditField().performAction(AccessibilityNodeInfo.ACTION_CUT))
            awaitReadingCondition("cut did not preserve the original tab on the clipboard") {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text.substring(tab, tab + 6) &&
                    draft.textFieldState.text.toString() == text.removeRange(tab, tab + 6)
            }
            runOnMainSync { session.flushPendingEdit() }
            awaitReadingCondition("cut did not commit") { session.canUndo && !session.state.hasActiveDraftChanges }
            runOnMainSync { session.requestUndo() }
            awaitReadingCondition("cut was not undone exactly in one step") {
                session.activeDraft?.textFieldState?.text?.toString() == text && session.canRedo
            }
            runOnMainSync {
                if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text.substring(tab, tab + 6)) {
                    clipboard.clearPrimaryClip()
                }
            }
            val expected = preferences()
            val file = File(targetContext.noBackupFilesDir, "text-appearance.properties")
            awaitReadingCondition("appearance settings were not persisted") {
                runCatching { file.bufferedReader().use(::readDocumentAppearances) == expected }.getOrDefault(false)
            }
            // Reset is scoped to this view and returns to following defaults, including explicit equal values.
            runOnMainSync { store.update(AppearanceGroup.Source, TextAppearance()) }
            check(preferences().markdown == expected.markdown)
            check(preferences().source.custom.isEmpty())
        }
        val longText = (1..70).joinToString("\n\n") { index ->
            "Paragraph $index: " + "Readable words with café and 😀. ".repeat(12)
        }
        // Keep a bounded page of source paragraphs tall enough to scroll even on a large tablet.
        store.update(AppearanceGroup.Source, TextAppearance()
            .change(AppearanceOption.Size, 22f).change(AppearanceOption.ColumnWidth, 320f))
        withReadingPage(longText, viewOnly = true) { _, session ->
            awaitReadingCondition("initial reading restoration did not finish") {
                (session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.scrollRestoration == null
            }
            runBlocking {
                withContext(AndroidUiDispatcher.Main) { session.markdownPreviewListState.animateScrollToItem(25, 15) }
            }
            waitForAccessibilityIdle()
            val readingIndex = session.markdownPreviewListState.firstVisibleItemIndex
            runOnMainSync {
                store.update(AppearanceGroup.Markdown, TextAppearance()
                    .change(AppearanceOption.Size, 30f)
                    .change(AppearanceOption.Font, 1f)
                    .change(AppearanceOption.ParagraphSpacing, 1.5f))
            }
            waitForAccessibilityIdle()
            try { awaitReadingCondition("reading reflow did not restore the visible paragraph") {
                (session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.scrollRestoration == null &&
                    session.markdownPreviewListState.firstVisibleItemIndex == readingIndex
            } } catch (failure: AssertionError) {
                throw AssertionError("reading reflow: expected=$readingIndex, " +
                    "actual=${session.markdownPreviewListState.firstVisibleItemIndex}, " +
                    "restoration=${(session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.scrollRestoration}", failure)
            }
            runOnMainSync { session.showTextEditor() }
            val paragraphStart = longText.indexOf("Paragraph ${readingIndex + 1}:").toLong()
            try { awaitReadingCondition("view-only source did not restore its reading position") {
                session.readOnlySourceScrollRestoration == null &&
                    session.viewportListState.layoutInfo.visibleItemsInfo.any { item ->
                        session.state.blocks.any { block -> item.key == block.key &&
                            paragraphStart in block.block.globalUtf16Start until
                                block.block.globalUtf16Start + block.block.text.length }
                    }
            } } catch (failure: AssertionError) {
                throw AssertionError("source reflow: presentation=${session.presentation}, " +
                    "restoration=${session.readOnlySourceScrollRestoration}, paragraph=$paragraphStart, " +
                    "first=${session.viewportListState.firstVisibleItemIndex}, " +
                    "blocks=${session.state.blocks.map { it.block.globalUtf16Start to it.block.globalUtf16End }}", failure)
            }
            waitForAccessibilityIdle()
            fun firstVisibleSourceStart(): Long? {
                val layout = session.viewportListState.layoutInfo
                return layout.visibleItemsInfo.firstNotNullOfOrNull { item ->
                    // LazyColumn includes rows in its leading content padding.
                    if (item.offset + item.size <= layout.viewportStartOffset.coerceAtLeast(0)) return@firstNotNullOfOrNull null
                    session.state.blocks.firstOrNull { it.key == item.key && it.block.text.isNotEmpty() }
                        ?.block?.globalUtf16Start
                }
            }
            // Give the reflow a known text line at the top, independently of the
            // partially visible rows left by the Markdown-to-source navigation.
            val sourceStart = runBlocking {
                withContext(AndroidUiDispatcher.Main) {
                    val target = session.state.blocks.withIndex().filter { it.value.block.text.isNotEmpty() }[1]
                    session.viewportListState.scrollToItem(target.index, 8)
                    target.value.block.globalUtf16Start
                }
            }
            awaitReadingCondition("source paragraph did not reach the top before reflow") {
                session.readOnlySourceScrollRestoration == null && firstVisibleSourceStart() == sourceStart
            }
            waitForAccessibilityIdle()
            runOnMainSync {
                store.update(AppearanceGroup.Source, TextAppearance()
                    .change(AppearanceOption.Size, 28f)
                    .change(AppearanceOption.ColumnWidth, 300f)
                    .change(AppearanceOption.LineSpacing, 2f))
            }
            waitForAccessibilityIdle()
            awaitReadingCondition("view-only source reflow lost the visible paragraph") {
                session.readOnlySourceScrollRestoration == null &&
                    firstVisibleSourceStart() == sourceStart
            }
        }
        withReadingPage(text) { activity, session ->
            requireActionableContentDescription("Show source text").performRequiredClick()
            waitForEditField()
            waitForAccessibilityIdle()
            runOnMainSync { activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
            awaitReadingCondition("source keyboard did not close before opening appearance") {
                activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true
            }
            waitForAccessibilityIdle()
            requireActionableContentDescription("More options").performRequiredClick()
            requireActionableText("Text appearance").performRequiredClick()
            awaitReadingCondition("appearance controls reopened the source keyboard") {
                session.activeDraft?.isEditorFocused == false &&
                    activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true
            }
            awaitReadingCondition("appearance tool did not acquire its window") {
                uiAutomation.clearCache()
                val documentWindow = activity.window.decorView.createAccessibilityNodeInfo().windowId
                uiAutomation.windows.any { window ->
                    window.isFocused && (window.id != documentWindow || activity.hasWindowFocus()) &&
                        window.root?.findNode { it.contentDescription == "Choose appearance group" } != null
                }
            }
            documentKey(android.view.KeyEvent.KEYCODE_H, android.view.KeyEvent.META_CTRL_ON)
            awaitReadingCondition("Ctrl+H did not open Replace from Appearance") {
                session.isFindVisible && session.isReplaceVisible
            }
            awaitReadingCondition("Appearance stayed open after Replace") {
                uiAutomation.clearCache()
                uiAutomation.rootInActiveWindow?.findNode { it.contentDescription == "Choose appearance group" } == null
            }
            for (key in listOf(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_H)) {
                requireActionableContentDescription("Close Find").performRequiredClick()
                requireActionableContentDescription("More options").performRequiredClick()
                requireActionableText("Text appearance").performRequiredClick()
                val before = preferences().source
                val size = before.value(AppearanceOption.Size, AppearanceGroup.Source)
                openSizeEditor(dev.soupslurpr.beautyxt.ui.editor.appearanceNumber(size))
                typeInNumberDialog("37")
                waitForAccessibilityNode("unfinished shortcut draft") { it.isEditable && it.text.toString() == "37" }
                documentKey(key, KeyEvent.META_CTRL_ON)
                awaitReadingCondition("numeric editor kept the Find shortcut") {
                    session.isFindVisible && session.isReplaceVisible == (key == KeyEvent.KEYCODE_H) &&
                        uiAutomation.rootInActiveWindow?.findNode { it.text?.toString() == "Apply" } == null
                }
                check(preferences().source == before) { "opening Find committed the unfinished number" }
            }
        }
        val wideSource = "\t".repeat(16) + "Long source line ".repeat(700)
        runOnMainSync {
            store.update(AppearanceGroup.Source, TextAppearance()
                .change(AppearanceOption.Size, 72f)
                .change(AppearanceOption.TabWidth, 16f)
                .change(AppearanceOption.Wrap, 0f))
        }
        withReadingPage(wideSource, initialPresentation = EditorPresentation.Text) { _, session ->
            waitForEditField()
            check(session.activeDraft?.textFieldState?.text?.toString() == wideSource)
        }
    } finally {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = originalServiceFlags }
        runOnMainSync {
            store.update(AppearanceGroup.Source, original.source)
            store.update(AppearanceGroup.Markdown, original.markdown)
        }
        awaitReadingCondition("original appearance settings were not restored") {
            runCatching {
                File(targetContext.noBackupFilesDir, "text-appearance.properties")
                    .bufferedReader().use(::readDocumentAppearances) == original
            }.getOrDefault(false)
        }
    }
}

/** Checks the fonts and sizes actually passed to prose, generated labels and standalone art. */
private fun Instrumentation.verifyLargeMarkdownListMarkers(store: TextAppearanceStore) {
    store.update(AppearanceGroup.Markdown, TextAppearance().change(AppearanceOption.Size, 72f)
        .change(AppearanceOption.ColumnWidth, 240f))
    for ((markdown, marker) in listOf("123456789. Hi" to "123456789.", "> > > > > > - [x] Hi" to "☑ ")) {
        withReadingPage(markdown) { activity, _ ->
            fun bounds(text: String) = Rect().also(waitForAccessibilityNode("large list text '$text'") {
                it.isVisibleToUser && it.text?.toString() == text
            }::getBoundsInScreen)
            val label = bounds(marker)
            val body = bounds("Hi")
            check(body.top >= label.bottom && body.width() >= 96 * activity.resources.displayMetrics.density - 1) {
                "large marker crowded the list body: marker=$label, body=$body"
            }
        }
    }
}

private fun Instrumentation.verifyMarkdownAppearanceRoles(store: TextAppearanceStore) {
    store.update(AppearanceGroup.Markdown, TextAppearance()
        .change(AppearanceOption.Font, 1f)
        .change(AppearanceOption.CodeFont, 2f)
        .change(AppearanceOption.Weight, 600f)
        .change(AppearanceOption.Size, 36f))
    val list = "1234. Prose\n\n      Continuation\n\n- [x] Task"
    withReadingPage(list) { activity, _ ->
        awaitReadingCondition("list markers did not follow the body size, family and weight") {
            val layouts = highlightTextLayouts(activity.window.decorView).associateBy { it.layoutInput.text.text }
            val body = layouts["Prose"]?.layoutInput?.style
            val marker = layouts["1234."] ?: return@awaitReadingCondition false
            val markerStyle = marker.layoutInput.style
            val task = layouts["☑ "]?.layoutInput?.style
            body?.fontSize?.value == 36f && body.fontWeight?.weight == 600 &&
                markerStyle.fontFamily == FontFamily.Serif && markerStyle.fontSize == body.fontSize &&
                markerStyle.fontWeight?.weight == 800 && !marker.hasVisualOverflow &&
                task?.fontSize == body.fontSize && task.fontFamily == FontFamily.Serif
        }
        fun textLeft(text: String): Int = Rect().also(waitForAccessibilityNode(text) {
            it.isVisibleToUser && it.text?.toString() == text
        }::getBoundsInScreen).left
        check(textLeft("Prose") == textLeft("Continuation")) { "multi-digit list continuation moved out of alignment" }
    }
    withReadingPage("See [^one].\n\n[^one]: Note") { activity, _ ->
        awaitReadingCondition("footnote number and return label did not scale with the selected family") {
            val layouts = highlightTextLayouts(activity.window.decorView).map { it.layoutInput }
            listOf("1", "↩").all { text -> layouts.any {
                it.text.text == text && it.style.fontSize.value == 28f && it.style.fontFamily == FontFamily.Serif
            } }
        }
    }
    for ((source, expectedText, family) in listOf(
        Triple("```mermaid\nflowchart LR\nA --> B\n```", "\uFFFC", FontFamily.Serif),
        Triple("\$\$x+y\$\$", "\uFFFC", FontFamily.Serif),
        Triple("```\nliteral\n```", "literal", FontFamily.Monospace)
    )) {
        withReadingPage(source) { activity, _ ->
            var styles = emptyList<Pair<String, FontFamily?>>()
            try {
                awaitReadingCondition("wrong font for $source") {
                    styles = highlightTextLayouts(activity.window.decorView).map {
                        it.layoutInput.text.text.trimEnd('\n') to it.layoutInput.style.fontFamily
                    }
                    (expectedText to family) in styles
                }
            } catch (failure: AssertionError) {
                throw AssertionError("${failure.message}, measured $styles", failure)
            }
        }
    }
}

/** Accessibility scrolls must stay inside each viewport, including at its ends. */
private fun Instrumentation.verifyAppearanceAccessibleScrolling() {
    waitForAccessibilityNode("expand appearance sheet") { node ->
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND }
    }.let { check(it.performAction(AccessibilityNodeInfo.ACTION_EXPAND)) }
    val sample = waitForAccessibilityNode("appearance sample") { it.text?.toString() == "Sample text" }
    val sampleControl = checkNotNull(sample.enabledClickableAncestor())
    if (sampleControl.stateDescription?.toString() == "Collapsed") sampleControl.performRequiredClick()
    waitForAccessibilityIdle()
    fun titleBounds() = Rect().also(waitForAccessibilityNode("appearance heading") {
        it.text?.toString() == "Text appearance" && it.isHeading
    }::getBoundsInScreen)
    val anchoredTitle = titleBounds()
    for (controls in listOf(false, true)) {
        fun viewport() = waitForAccessibilityNode(if (controls) "appearance controls" else "scrolling sample") {
            it.isScrollable && it.rangeInfo == null && (it.collectionInfo != null) == controls
        }
        // Reach the bottom, then scroll back by a full page. The final partial
        // page must not forward its unused delta to the containing modal sheet.
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            var count = 0
            while (true) {
                val node = viewport()
                if (node.actionList.none { it.id == action }) break
                check(++count <= 20) { "appearance viewport never reached its end: controls=$controls, action=$action, node=$node" }
                check(node.performAction(action)) { "appearance scroll was rejected: controls=$controls, action=$action, node=$node" }
                // A brief gap between accessibility events is not animation completion.
                android.os.SystemClock.sleep(600)
                waitForAccessibilityIdle()
                check(titleBounds() == anchoredTitle) { "accessibility scrolling displaced the appearance sheet" }
            }
            check(count > 0) { "appearance fixture did not exercise scrolling" }
        }
    }
    // Leave the controls unobstructed for the rest of this workflow.
    waitForAccessibilityNode("expanded sample") { it.text?.toString() == "Sample text" }
        .enabledClickableAncestor()!!.performRequiredClick()
}
