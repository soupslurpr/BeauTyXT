/* Verifies reading-page taps without borrowing gestures from the document's controls. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.activity.compose.setContent
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.ActiveEditDraft
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val SHORT_READING_TEXT = "A short document."

/** Checks empty and short pages, page gutters, scrolling, long presses, and read-only sources. */
internal fun Instrumentation.verifyMarkdownBackgroundTaps() {
    for (text in listOf(SHORT_READING_TEXT, "")) {
        withReadingPage(text) { activity, session ->
            val page = readingPageBounds()
            val blankY = page.top + page.height() * 0.78f
            // Neither a swipe nor a held touch in blank space is an edit request.
            readingPageGesture(page.exactCenterX(), blankY, distanceY = -200f)
            requireStillReading(activity, session, "blank swipe (${text.length} characters)")
            readingPageGesture(
                page.exactCenterX(),
                blankY,
                durationMillis = ViewConfiguration.getLongPressTimeout() + 100L
            )
            requireStillReading(activity, session, "blank long press (${text.length} characters)")
            readingPageGesture(page.exactCenterX(), blankY)
            awaitReadingCondition("blank-page tap did not open the editor at the source end") {
                session.presentation == EditorPresentation.Text &&
                    session.activeDraft?.textFieldState?.text?.toString() == text &&
                    session.activeDraft?.sourceCaret() == text.length.toLong() &&
                    activity.window.decorView.rootWindowInsets
                        ?.isVisible(WindowInsets.Type.ime()) == true
            }
            check(!session.state.hasDocumentChanges) { "tapping the page changed the document" }
        }
    }
    withReadingPage(SHORT_READING_TEXT) { _, session ->
        val page = readingPageBounds()
        val line = waitForAccessibilityNode("paragraph beside the page gutter") {
            it.text?.toString() == SHORT_READING_TEXT
        }
        val lineBounds = Rect().also(line::getBoundsInScreen)
        // Beside the text is not beneath it: preserve the retained caret here.
        readingPageGesture(page.left + 2f, lineBounds.exactCenterY())
        awaitReadingCondition("side-gutter tap did not retain the source position") {
            session.presentation == EditorPresentation.Text &&
                session.activeDraft?.sourceCaret() == 0L
        }
    }
    withReadingPage("One tall final paragraph. ".repeat(100)) { _, session ->
        awaitReadingCondition("tall final-block fixture does not extend below the viewport") {
            val ready = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
            val layout = session.markdownPreviewListState.layoutInfo
            ready.layout.items.size == 1 &&
                layout.visibleItemsInfo.singleOrNull()?.size?.let { height ->
                    height > layout.viewportSize.height
                } == true
        }
        val page = readingPageBounds()
        readingPageGesture(page.left + 2f, page.exactCenterY())
        awaitReadingCondition("gutter beside a partially visible final block moved to its end") {
            session.presentation == EditorPresentation.Text &&
                session.activeDraft?.sourceCaret() == 0L
        }
    }
    for (text in listOf(
        "## Café 😺\n\nText.\n\n",
        " \n\n",
        "```text\nLast code.\n```\n",
        "| Final | Table |\n| --- | --- |\n| A | B |\n"
    )) {
        withReadingPage(text) { _, session ->
            val page = readingPageBounds()
            readingPageGesture(page.exactCenterX(), page.top + page.height() * 0.78f)
            awaitReadingCondition("tap below the final block did not reach the full source end") {
                session.presentation == EditorPresentation.Text &&
                    session.activeDraft?.sourceCaret() == text.length.toLong()
            }
            check(!session.state.hasDocumentChanges) { "moving to the end changed the document" }
        }
    }
    withReadingPage("First paragraph.\n\nSecond paragraph.") { _, session ->
        val first = waitForAccessibilityNode("first paragraph") {
            it.text?.toString() == "First paragraph."
        }
        val second = waitForAccessibilityNode("second paragraph") {
            it.text?.toString() == "Second paragraph."
        }
        val firstBounds = Rect().also(first::getBoundsInScreen)
        val secondBounds = Rect().also(second::getBoundsInScreen)
        check(secondBounds.top > firstBounds.bottom) { "paragraph-gap fixture has no gap" }
        readingPageGesture(firstBounds.exactCenterX(), (firstBounds.bottom + secondBounds.top) / 2f)
        awaitReadingCondition("paragraph-gap tap did not retain the source position") {
            session.presentation == EditorPresentation.Text &&
                session.activeDraft?.sourceCaret() == 0L
        }
    }
    withReadingPage(SHORT_READING_TEXT) { activity, session ->
        val line = waitForAccessibilityNode("short rendered paragraph") {
            it.text?.toString() == SHORT_READING_TEXT
        }
        val lineBounds = Rect().also(line::getBoundsInScreen)
        readingPageGesture(
            lineBounds.left + 30f,
            lineBounds.exactCenterY(),
            durationMillis = ViewConfiguration.getLongPressTimeout() + 100L
        )
        requireStillReading(activity, session)
    }
    withReadingPage("[Missing heading](#absent)") { activity, session ->
        val link = waitForAccessibilityNode("rendered heading link") {
            it.text?.toString() == "Missing heading"
        }
        val bounds = Rect().also(link::getBoundsInScreen)
        readingPageGesture(bounds.left + 30f, bounds.exactCenterY())
        waitForAccessibilityNode("heading-link dialog") {
            it.text?.toString() == targetContext.getString(R.string.link_heading_missing_title)
        }
        requireStillReading(activity, session)
    }
    withReadingPage("```text\nCopy this snippet.\n```") { activity, session ->
        val copy = requireActionableContentDescription("Copy code")
        val bounds = Rect().also(copy::getBoundsInScreen)
        val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
        try {
            readingPageGesture(bounds.exactCenterX(), bounds.exactCenterY())
            requireStillReading(activity, session)
            runOnMainSync {
                check(
                    clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "Copy this snippet.\n"
                ) {
                    "tapping the code action did not copy its contents"
                }
            }
        } finally {
            runOnMainSync { clipboard.clearPrimaryClip() }
        }
    }
    for (text in listOf(SHORT_READING_TEXT, "")) {
        withReadingPage(text, viewOnly = true) { activity, session ->
            val page = readingPageBounds()
            readingPageGesture(page.exactCenterX(), page.top + page.height() * 0.78f)
            requireStillReading(activity, session)
            check(session.isViewOnly && session.activeDraft == null)
        }
    }
    val longText = (1..120).joinToString("\n\n") { "Paragraph $it. Read and scroll." }
    withReadingPage(longText) { activity, session ->
        val page = readingPageBounds()
        readingPageGesture(page.exactCenterX(), page.centerY() + 200f, distanceY = -400f)
        requireStillReading(activity, session)
        awaitReadingCondition("reading-page swipe did not scroll the document") {
            session.markdownPreviewListState.firstVisibleItemIndex > 0 ||
                session.markdownPreviewListState.firstVisibleItemScrollOffset > 0
        }
        awaitReadingCondition("reading-page fling did not settle") {
            !session.markdownPreviewListState.isScrollInProgress
        }
        readingPageGesture(page.left + 2f, page.exactCenterY())
        awaitReadingCondition("scrolled page-gutter tap did not retain a later source position") {
            val draft = session.activeDraft
            session.presentation == EditorPresentation.Text && draft != null &&
                draft.sourceCaret() in 1L until longText.length.toLong()
        }
        check(!session.state.hasDocumentChanges) { "navigation changed the long document" }
    }
    withReadingPage(longText) { _, session ->
        runBlocking {
            withContext(Dispatchers.Main) {
                val ready = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
                session.markdownPreviewListState.scrollToItem(ready.layout.items.lastIndex)
            }
        }
        waitForAccessibilityIdle()
        val page = readingPageBounds()
        val last = waitForAccessibilityNode("final paragraph after scrolling") {
            it.text?.toString() == "Paragraph 120. Read and scroll."
        }
        val lastBounds = Rect().also(last::getBoundsInScreen)
        val blankY = lastBounds.bottom + 8f
        check(blankY < page.bottom) { "scrolled-end fixture has no visible trailing page" }
        readingPageGesture(page.left + 2f, blankY)
        awaitReadingCondition("tap below the scrolled document did not reach the source end") {
            session.presentation == EditorPresentation.Text &&
                session.activeDraft?.sourceCaret() == longText.length.toLong()
        }
        check(!session.state.hasDocumentChanges) { "end navigation changed the long document" }
    }
}

/** Resolves the visible editor's local caret against the document's UTF-16 range. */
private fun ActiveEditDraft.sourceCaret(): Long =
    edit.snapshot.range.start + textFieldState.selection.start

/** Presents the production screen with an isolated Markdown worker and disposable source. */
internal fun Instrumentation.withReadingPage(
    text: String,
    viewOnly: Boolean = false,
    verify: (HomeActivity, EditorSession) -> Unit
) {
    val document = RustDocument.createEmpty()
    val metrics = document.replace(0L, Utf16Range(0L, 0L), text)
    val source = if (viewOnly) {
        object : EditorDocumentSource {
            override fun matchesSourceUri(encodedUri: String) = false
            override fun encodedShareUri(): String? = null
            override fun close() = Unit
        }
    } else {
        null
    }
    val session = EditorSession(
        title = "Reading taps.md",
        state = EditorDocumentState(document, initialRevision = metrics.revision),
        documentSource = source,
        initialPresentation = EditorPresentation.MarkdownPreview,
        markdownRenderer = IsolatedMarkdownRenderer(targetContext)
    )
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    try {
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    DocumentEditor(session, activity::finish, closesDocumentTask = false)
                }
            }
            session.openInitialEditor()
        }
        awaitReadingCondition("reading page did not become ready") {
            session.markdownPreviewStatus is MarkdownPreviewStatus.Ready
        }
        waitForAccessibilityIdle()
        verify(activity, session)
    } finally {
        runOnMainSync {
            activity.finishAndRemoveTask()
            session.close()
        }
        waitForAccessibilityIdle()
    }
}

/** Uses real on-screen controls to keep taps between chrome and the bottom toolbar. */
private fun Instrumentation.readingPageBounds(): Rect {
    val root = checkNotNull(uiAutomation.rootInActiveWindow)
    val bounds = Rect().also(root::getBoundsInScreen)
    val title = requireActionableContentDescription("More options")
    val mode = requireActionableContentDescription("Show source text")
    val titleBounds = Rect().also(title::getBoundsInScreen)
    val modeBounds = Rect().also(mode::getBoundsInScreen)
    bounds.top = titleBounds.bottom
    bounds.bottom = modeBounds.top
    return bounds
}

/** Injects complete touch gestures so cancellation and scroll arbitration are exercised. */
internal fun Instrumentation.readingPageGesture(
    x: Float,
    y: Float,
    distanceY: Float = 0f,
    durationMillis: Long = 96L
) {
    val downTime = SystemClock.uptimeMillis()
    val steps = 6
    for (step in 0..steps) {
        val action = when (step) {
            0 -> MotionEvent.ACTION_DOWN
            steps -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_MOVE
        }
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            x,
            y + distanceY * step / steps,
            0
        )
        try {
            check(uiAutomation.injectInputEvent(event, true)) { "reading-page touch was rejected" }
        } finally {
            event.recycle()
        }
        if (step < steps) SystemClock.sleep(durationMillis / steps)
    }
}

internal fun Instrumentation.requireStillReading(
    activity: HomeActivity,
    session: EditorSession,
    gesture: String = "non-editing reading-page gesture"
) {
    waitForAccessibilityIdle()
    runBlocking {
        withContext(Dispatchers.Main) {
            check(session.presentation == EditorPresentation.MarkdownPreview) {
                "$gesture opened the source editor"
            }
            check(
                activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) !=
                    true
            ) {
                "$gesture opened the keyboard"
            }
        }
    }
}

internal fun awaitReadingCondition(description: String, condition: () -> Boolean) = runBlocking {
    withContext(Dispatchers.Main) {
        try {
            withTimeout(10_000L) { while (!condition()) delay(16L) }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(description, failure)
        }
    }
}
