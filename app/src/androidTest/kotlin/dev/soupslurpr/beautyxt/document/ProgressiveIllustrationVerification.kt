package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Debug
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.AndroidUiDispatcher
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Visits more unique real native illustrations than the cache holds, then revisits evicted ones. */
internal fun Instrumentation.verifyProgressiveIllustrationsUi() {
    fun formula(index: Int) = "\\frac{$index}{${index + 1}}"
    val source = buildString {
        append("# A long illustrated document\n\n")
        for (index in 1..140) {
            append("## Section $index\n\n$$${formula(index)}$$\n\n")
        }
        append("## Complete diagram\n\n```mermaid\nflowchart TD\n%% ")
        append("a".repeat(4_500))
        append("\nA[Read]-->B[Share]\n```\n")
    }
    val document = RustDocument.createEmpty()
    val metrics = document.replace(0L, Utf16Range(0, 0), source)
    val session = EditorSession(
        "Progressive illustrations.md",
        EditorDocumentState(document, initialRevision = metrics.revision),
        initialPresentation = EditorPresentation.MarkdownPreview,
        markdownRenderer = IsolatedMarkdownRenderer(targetContext)
    )
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    val root = File(targetContext.getExternalFilesDir(null), "native-math-verification")
    check(root.mkdirs() || root.isDirectory)
    val samples = mutableListOf<String>()
    try {
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    DocumentEditor(session, activity::finish, closesDocumentTask = false)
                }
            }
        }
        val ready = runBlocking {
            withContext(Dispatchers.Main) {
                session.openInitialEditor()
                withTimeout(15_000) {
                    snapshotFlow { session.markdownPreviewStatus }
                        .first { it is MarkdownPreviewStatus.Ready } as MarkdownPreviewStatus.Ready
                }
            }
        }
        for (index in (1..140).toList() + listOf(70, 1, 140)) {
            val formula = formula(index)
            val item = ready.layout.items.indexOfFirst { it.blocks.any { it.text == formula } }
            check(item >= 0)
            runBlocking {
                withContext(Dispatchers.Main) {
                    session.markdownPreviewListState.scrollToItem(item)
                }
            }
            waitForAccessibilityNode("rendered formula $index past the lifetime quota") {
                it.isVisibleToUser && it.contentDescription?.toString() == "Formula: $formula"
            }
            if (index in listOf(1, 70, 140)) {
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                samples += "section=$index appPssKiB=${memory.totalPss}"
                File(root, "progressive-memory-samples.txt").writeText(samples.joinToString("\n"))
            }
        }
        // Exercise a long animated scroll while illustration results are queued; no source field
        // or keyboard should activate, and no late result should start a second scroll afterward.
        runBlocking {
            withContext(AndroidUiDispatcher.Main) {
                session.markdownPreviewListState.animateScrollToItem(0)
                session.markdownPreviewListState.animateScrollToItem(ready.layout.items.lastIndex)
            }
        }
        waitForAccessibilityNode("complete fragmented diagram rendered at the document end") {
            it.isVisibleToUser &&
                it.contentDescription?.toString()?.startsWith("Diagram: flowchart TD") == true
        }
        waitForAccessibilityNode("progressive work has settled") { node ->
            node.parent == null &&
                node.findAccessibilityNodeInfosByText("Rendering…").none { it.isVisibleToUser }
        }
        val position = runBlocking {
            withContext(Dispatchers.Main) {
                delay(200)
                session.markdownPreviewListState.let {
                    check(!it.isScrollInProgress)
                    it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset
                }
            }
        }
        runBlocking {
            delay(500)
            withContext(Dispatchers.Main) {
                val list = session.markdownPreviewListState
                check(position == (list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset))
                check(session.presentation == EditorPresentation.MarkdownPreview)
                check(session.state.metrics?.revision == metrics.revision)
                check(
                    !activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime())
                )
            }
        }
        val image = checkNotNull(uiAutomation.takeScreenshot())
        File(root, "progressive-end-preview.webp").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
        }
        image.recycle()
        File(root, "progressive-memory-samples.txt").writeText(samples.joinToString("\n"))
    } finally {
        runOnMainSync {
            session.close()
            activity.finish()
        }
    }
}
