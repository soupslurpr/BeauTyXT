package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.CHECKED_STATE_FALSE
import android.view.accessibility.AccessibilityNodeInfo.CHECKED_STATE_TRUE
import dev.soupslurpr.beautyxt.ui.editor.DocumentSelection
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.ExcerptFormat
import dev.soupslurpr.beautyxt.ui.editor.ReadingPoint
import java.io.File

/** Exercises the production accessibility actions, keyboard routing, and frozen output chooser. */
internal fun Instrumentation.verifyDocumentSelectionControls() {
    // Deliberately split one extended character as if a bounded renderer had to fragment it.
    val left = dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock(dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind.Paragraph,
        false, false, false, false, false, false, 0, 0, 0, 0, "Text e", "", emptyList())
    val right = left.copy(text = "́ continues", continuesPrevious = true)
    val fragmented = dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument(100, listOf(left, right), 0, false)
    check(dev.soupslurpr.beautyxt.ui.editor.snapReadingGrapheme(fragmented, ReadingPoint(1, 0), false) == ReadingPoint(0, 5))
    check(dev.soupslurpr.beautyxt.ui.editor.snapReadingGrapheme(fragmented, ReadingPoint(0, 6), true) == ReadingPoint(1, 1))
    withReadingPage("Alpha 👩‍🔬 é text.\n\nSecond paragraph.\n\nPRIVATE TAIL") { activity, session ->
        val text = waitForAccessibilityNode("reading selection text") { it.text?.toString() == "Alpha 👩‍🔬 é text." }
        // TalkBack's Actions menu must expose both actions on the same paragraph.
        // Separate semantics modifiers used to let Edit source text hide Selection.
        check(text.actionList.any { it.label?.toString() == "Edit source text" })
        val select = checkNotNull(text.actionList.firstOrNull { it.label?.toString() == "Selection" }) {
            "Reading paragraph lost its TalkBack Selection action: ${text.actionList}"
        }
        check(text.performAction(select.id))
        awaitReadingCondition("TalkBack action did not select the paragraph") {
            (session.documentSelection as? DocumentSelection.Reading)?.end == ReadingPoint(0, "Alpha 👩‍🔬 é text.".length)
        }
        check(text.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 5)
        }))
        awaitReadingCondition("accessibility selection was not retained") {
            (session.documentSelection as? DocumentSelection.Reading)?.end == ReadingPoint(0, 5)
        }
        val handle = waitForAccessibilityNode("selection end handle") { it.contentDescription?.toString() == "Selection end" }
        val next = checkNotNull(handle.actionList.firstOrNull { it.label?.toString() == "Move boundary to next character" })
        check(handle.performAction(next.id))
        awaitReadingCondition("accessible handle did not advance") {
            (session.documentSelection as? DocumentSelection.Reading)?.end == ReadingPoint(0, 6)
        }
        val refreshed = waitForAccessibilityNode("selection end handle") { it.contentDescription?.toString() == "Selection end" }
        check(refreshed.performAction(next.id))
        awaitReadingCondition("handle split a joined emoji") {
            (session.documentSelection as? DocumentSelection.Reading)?.end == ReadingPoint(0, 11)
        }
        selectionControl("Selection actions").performRequiredClick()
        selectionControl("Extend selection to next paragraph").performRequiredClick()
        awaitReadingCondition("selection did not cross paragraphs") {
            (session.documentSelection as? DocumentSelection.Reading)?.end == ReadingPoint(1, 17)
        }
        val end = waitForAccessibilityNode("selection end handle") { it.contentDescription?.toString() == "Selection end" }
        check(end.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        waitForAccessibilityNode("focused selection handle") { it.contentDescription?.toString() == "Selection end" && it.isFocused }
        waitForAccessibilityIdle()
        val time = SystemClock.uptimeMillis()
        sendKeySync(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON))
        sendKeySync(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON))
        val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
        try { awaitReadingCondition("keyboard Copy lost displayed text or paragraph boundaries") {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "Alpha 👩‍🔬 é text.\n\nSecond paragraph."
        } } catch (failure: AssertionError) {
            error("${failure.message}; clipboard=${clipboard.primaryClip?.getItemAt(0)?.text}; selection=${session.documentSelection}")
        }
        selectionControl("Send/export").performRequiredClick()
        awaitReadingCondition("excerpt preview did not prepare") { session.excerptExport.prepared != null && !session.excerptExport.busy }
        selectionControl("Preview and settings").performRequiredClick()
        selectionControl("Options").performRequiredClick()
        waitForAccessibilityNode("export options dialog") { it.text?.toString() == "Share as a file" }
        val shareSwitch = scrollToExcerptSwitch("Share as a file")
        check(shareSwitch.isClickable)
        check(shareSwitch.checked == CHECKED_STATE_FALSE)
        shareSwitch.performRequiredClick()
        awaitReadingCondition("accessible share switch did not select file output") { session.excerptExport.shareAsFile && !session.excerptExport.busy }
        val checkedShareSwitch = scrollToExcerptSwitch("Share as a file")
        check(checkedShareSwitch.checked == CHECKED_STATE_TRUE)
        checkedShareSwitch.performRequiredClick()
        awaitReadingCondition("accessible share switch did not restore text output") { !session.excerptExport.shareAsFile && !session.excerptExport.busy }
        selectionControl("Done").performRequiredClick()
        selectionControl("Markdown").performRequiredClick()
        awaitReadingCondition("selected Markdown did not prepare") {
            session.excerptExport.format == ExcerptFormat.Markdown && session.excerptExport.prepared != null && !session.excerptExport.busy
        }
        check(session.excerptExport.prepared!!.formatted!!.blocks.none { "PRIVATE TAIL" in it.text })
        selectionControl("PDF").performRequiredClick()
        awaitReadingCondition("selected PDF preview did not prepare") {
            session.excerptExport.format == ExcerptFormat.Pdf && session.excerptExport.prepared?.pdfPages == 1 && !session.excerptExport.busy
        }
        SystemClock.sleep(400)
        uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(targetContext.cacheDir, "document-selection-export.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        selectionControl("Page settings").performRequiredClick()
        waitForAccessibilityNode("expanded excerpt page settings") { it.text?.toString() == "Paper" }
        for ((label, value) in listOf<Pair<String, () -> Boolean>>(
            "Wrap long source lines" to { session.excerptExport.printDraft.wrapLongLines },
            "File name header" to { session.excerptExport.printDraft.showFileName },
            "Page numbers" to { session.excerptExport.printDraft.showPageNumbers }
        )) {
            val before = value()
            val control = scrollToExcerptSwitch(label)
            check(control.checked == if (before) CHECKED_STATE_TRUE else CHECKED_STATE_FALSE)
            control.performRequiredClick()
            awaitReadingCondition("accessible $label switch did not update the PDF") {
                value() != before && !session.excerptExport.busy && session.excerptExport.prepared?.pdfPages == 1
            }
            check(scrollToExcerptSwitch(label).checked == if (before) CHECKED_STATE_FALSE else CHECKED_STATE_TRUE)
        }
        runOnMainSync {
            check(session.excerptExport.usePreparedDestination(dev.soupslurpr.beautyxt.ui.editor.ExcerptDestination.Save))
            check(session.excerptExport.chooseSaveDestination())
        }
        waitForAccessibilityNode("disabled PDF settings during destination handoff") {
            it.isVisibleToUser && it.isCheckable && !it.isEnabled &&
                it.findNode { child -> child.text?.toString() == "Page numbers" } != null
        }
        runOnMainSync { session.excerptExport.destinationReturned(null) }
        waitForAccessibilityNode("enabled PDF settings after cancelled destination handoff") {
            it.isVisibleToUser && it.isCheckable && it.isEnabled &&
                it.findNode { child -> child.text?.toString() == "Page numbers" } != null
        }
        // Dismissing output retains the selection; the explicit Clear action dismisses it.
        runOnMainSync { session.excerptExport.dismiss() }
        selectionControl("Clear").performRequiredClick()
        requireActionableContentDescription("Show source text").performRequiredClick()
        awaitReadingCondition("source did not open") { session.presentation == EditorPresentation.Text && session.activeDraft != null }
        val field = waitForAccessibilityNode("source editing field") { it.isEditable && it.text?.contains("Alpha") == true }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 5)
        }))
        awaitReadingCondition("source field selection did not expose document outputs") {
            (session.documentSelection as? DocumentSelection.Source)?.range == Utf16Range(1, 5)
        }
        waitForAccessibilityNode("native source selection remains editable") {
            it.isEditable && it.textSelectionStart == 1 && it.textSelectionEnd == 5
        }
        selectionControl("Send/export")
        check(!session.state.hasDocumentChanges)
        runOnMainSync { clipboard.clearPrimaryClip() }
    }
    val references = (1..9).joinToString(" ") { "[^n$it]" }
    val definitions = (1..10).joinToString("\n\n") { "[^n$it]: Note $it" }
    withReadingPage("$references\n\nSee [^n10] here\n\n$definitions") { _, session ->
        val text = waitForAccessibilityNode("two-digit footnote marker") { it.text?.toString() == "See 10 here" }
        check(text.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 4)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 5)
        }))
        awaitReadingCondition("footnote digit did not remain selected") {
            (session.documentSelection as? DocumentSelection.Reading)?.end?.markerOffset == 1
        }
        kotlinx.coroutines.runBlocking { check(session.selectedPlainText(100) == "1") }
        selectionControl("Send/export").performRequiredClick()
        awaitReadingCondition("footnote digit excerpt did not prepare") { session.excerptExport.prepared != null && !session.excerptExport.busy }
        selectionControl("Markdown").performRequiredClick()
        awaitReadingCondition("footnote digit Markdown did not prepare") {
            session.excerptExport.format == ExcerptFormat.Markdown && session.excerptExport.prepared != null && !session.excerptExport.busy
        }
        check(session.excerptExport.prepared!!.formatted!!.blocks.single().text == "1")
        check(session.excerptExport.prepared!!.notices.isEmpty())
    }
}

private fun Instrumentation.selectionControl(label: String): AccessibilityNodeInfo {
    return requireActionableText(label)
}

/** Checks the displayed page number, accessible PDF content, and page boundaries together. */
internal fun Instrumentation.verifyExcerptPdfPages() {
    val text = (1..80).joinToString("\n") { "Page preview line $it" }
    withReadingPage(text, initialPresentation = EditorPresentation.Text) { activity, session ->
        runOnMainSync {
            check(session.selectSource(0, text.length.toLong()))
            session.excerptExport.open(activity, "Pages.txt")
        }
        awaitReadingCondition("excerpt did not prepare") { session.excerptExport.canApply }
        selectionControl("PDF").performRequiredClick()
        awaitReadingCondition("two-page excerpt PDF did not prepare") {
            session.excerptExport.format == ExcerptFormat.Pdf && session.excerptExport.prepared?.pdfPages == 2 && !session.excerptExport.busy
        }
        selectionControl("Preview and page settings").performRequiredClick()
        val expected = checkNotNull(session.excerptExport.prepared?.pdf).openReadOnly(targetContext).use { input ->
            PdfRenderer(input).use { reader ->
                (0 until reader.pageCount).map { index -> reader.openPage(index).use { page ->
                    page.textContents.joinToString("\n") { it.text }
                } }
            }
        }
        repeat(6) {
            waitForAccessibilityIdle()
            uiAutomation.clearCache()
            val root = checkNotNull(uiAutomation.rootInActiveWindow)
            if (root.findNode { it.isVisibleToUser && it.text?.toString() == "Next preview page" } == null) {
                val scroll = root.findNode { it.isVisibleToUser && it.isScrollable && it.actionList.any { action ->
                    action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } }
                check(scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
            }
        }
        fun requirePage(index: Int) {
            waitForAccessibilityNode("PDF preview page ${index + 1}") {
                it.isVisibleToUser && it.contentDescription?.toString() == expected[index]
            }
            waitForAccessibilityNode("matching PDF page number") {
                it.isVisibleToUser && it.text?.toString() == "Page ${index + 1} of 2"
            }
        }
        requirePage(0)
        selectionControl("Next preview page").performRequiredClick()
        requirePage(1)
        waitForAccessibilityNode("disabled Next at the last PDF page") { node ->
            !node.isEnabled && node.findNode { it.text?.toString() == "Next preview page" } != null
        }
        selectionControl("Previous preview page").performRequiredClick()
        requirePage(0)
        waitForAccessibilityNode("disabled Previous at the first PDF page") { node ->
            !node.isEnabled && node.findNode { it.text?.toString() == "Previous preview page" } != null
        }
        check(!session.state.hasDocumentChanges)
    }
}

/** Scrolls the export sheet until TalkBack can reach one named, stateful switch. */
private fun Instrumentation.scrollToExcerptSwitch(label: String): AccessibilityNodeInfo {
    repeat(6) {
        waitForAccessibilityIdle()
        uiAutomation.clearCache()
        val root = checkNotNull(uiAutomation.rootInActiveWindow)
        root.findNode { node -> node.isVisibleToUser && node.isCheckable &&
            node.findNode { it.text?.toString() == label } != null }?.let { return it }
        val scroll = root.findNode { it.isVisibleToUser && it.isScrollable && it.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } }
        check(scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
            val nodes = ArrayList<String>()
            root.findNode { node ->
                nodes += "${node.text} / visible=${node.isVisibleToUser} / checkable=${node.isCheckable} / scrollable=${node.isScrollable}"
                false
            }
            "Export sheet cannot reach its named $label switch: $nodes"
        }
    }
    error("Export sheet did not expose its named $label switch")
}

/** Run on a wide emulator window; one pane switches tools without hiding the passage. */
internal fun Instrumentation.verifyWideDocumentTools() {
    withReadingPage("# Overview\n\nA useful passage.\n\n# Summary\n\nAnother useful passage.") { activity, session ->
        check(activity.resources.configuration.screenWidthDp >= 840) { "This check needs a wide emulator window" }
        requireActionableContentDescription("Document contents").performRequiredClick()
        selectionControl("Summary").performRequiredClick()
        awaitReadingCondition("Contents jump did not create return history") { session.hasPreviousLocation }
        // The wide pane remains open after choosing a heading.
        selectionControl("Overview")
        selectionControl("Results").performRequiredClick()
        awaitReadingCondition("wide Find did not open") { session.isFindVisible && session.isFindResultsExpanded }
        awaitReadingCondition("hardware-style Find opened the software keyboard") {
            activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true
        }
        runOnMainSync { session.updateFindFieldValue(androidx.compose.ui.text.input.TextFieldValue("useful")) }
        awaitReadingCondition("wide reading search did not complete") { session.isFindComplete && session.findResults.size == 2 }
        requireActionableContentDescription("Next match").performRequiredClick()
        awaitReadingCondition("wide match was not selected") { session.findResultIndex >= 0 }
        waitForAccessibilityIdle()
        check(session.isFindResultsExpanded) { "wide match navigation collapsed its pane" }
        awaitReadingCondition("match navigation left the software keyboard open") {
            activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true
        }
        selectionControl("Contents").performRequiredClick()
        try { selectionControl("Summary") } catch (failure: Exception) {
            uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(targetContext.cacheDir, "document-experience-wide-failure.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { bitmap.recycle() }
            }
            val labels = ArrayList<String>()
            fun inspect(node: AccessibilityNodeInfo) {
                labels += "${node.text} / ${node.contentDescription} / clickable=${node.isClickable}"
                for (index in 0 until node.childCount) node.getChild(index)?.let(::inspect)
            }
            uiAutomation.rootInActiveWindow?.let(::inspect)
            error("${failure.message}; wide pane: $labels")
        }
        uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(targetContext.cacheDir, "document-experience-wide.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
    }
}
