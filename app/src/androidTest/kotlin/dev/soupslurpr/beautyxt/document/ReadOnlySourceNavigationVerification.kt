/* Verifies bounded reading-position navigation through Rust and the real source layout. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshotFlow
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Checks exact indexed source positions without any editable field or full-document viewport. */
internal fun verifyNativeSourcePositionNavigation() = runBlocking {
    val text = "# Start\n" + "x😀".repeat(8_000) + "\n" +
        (0 until 20_000).joinToString("\n") { "Line $it" } + "\n"
    val document = RustDocument.createEmpty()
    val metrics = document.replace(0L, Utf16Range(0L, 0L), text)
    EditorDocumentState(document, initialRevision = metrics.revision).use { state ->
        state.loadInitialViewport()
        val revision = checkNotNull(state.metrics).revision
        val positions = listOf(
            0L,
            text.indexOf("😀").toLong(),
            text.indexOf("Line 19000").toLong(),
            text.length.toLong()
        )
        for (offset in positions) {
            check(state.navigateToSourceOffset(revision, offset)) {
                "native source navigation failed at $offset: ${state.editorMessage}"
            }
            check(state.blocks.first().block.globalUtf16Start == offset)
            check(state.blocks.sumOf { it.block.text.length } <= 32 * 1024)
            check(state.activeEdit == null)
            check(!state.hasDocumentChanges)
        }
        check(!state.navigateToSourceOffset(revision, text.length + 1L))
        check(!state.navigateToSourceOffset(revision - 1L, 0L))
    }
}

/** Exercises preview-to-source anchoring in the production Compose read-only layout. */
internal fun Instrumentation.verifyReadOnlySourcePosition(capturePreview: Boolean = false) {
    val text = buildString {
        repeat(80) { index ->
            append("## Section $index\n\nParagraph $index: ")
            repeat(8) { append("Readable local text with café and 😀. ") }
            append("\n\n")
        }
    }
    val document = RustDocument.createEmpty()
    val metrics = document.replace(0L, Utf16Range(0L, 0L), text)
    val source = object : EditorDocumentSource {
        override fun matchesSourceUri(encodedUri: String) = false
        override fun encodedShareUri(): String? = null
        override fun close() = Unit
    }
    val session = EditorSession(
        title = "Reading position.md",
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
        runBlocking {
            withContext(Dispatchers.Main) {
                activity.setContent {
                    BeauTyXTTheme {
                        DocumentEditor(session, activity::finish, closesDocumentTask = false)
                    }
                }
                session.openInitialEditor()
                val ready = withTimeout(15_000) {
                    snapshotFlow { session.markdownPreviewStatus }
                        .first { it is MarkdownPreviewStatus.Ready }
                } as MarkdownPreviewStatus.Ready
                ready.scrollRestoration?.let {
                    session.consumeMarkdownPreviewScrollRestoration(ready.revision, it)
                }
                val offset = text.indexOf("Paragraph 60:").toLong()
                session.observeMarkdownPreviewViewportAnchor(ready.revision, offset, -7)
                session.showTextEditor()
                withTimeout(10_000) {
                    snapshotFlow {
                        session.presentation == EditorPresentation.Text &&
                            session.readOnlySourceScrollRestoration == null &&
                            session.viewportListState.layoutInfo.visibleItemsInfo.any { item ->
                                session.state.blocks.any { block ->
                                    item.key == block.key &&
                                        block.block.globalUtf16Start == offset
                                }
                            }
                    }.first { it }
                }
                check(session.isViewOnly && session.activeDraft == null)
            }
        }
        waitForAccessibilityIdle()
        if (capturePreview) {
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            try {
                File(targetContext.cacheDir, "read-only-source-position.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
    } finally {
        runOnMainSync {
            activity.finishAndRemoveTask()
            session.close()
        }
    }
}
