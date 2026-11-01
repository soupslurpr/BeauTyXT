package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus
import dev.soupslurpr.beautyxt.ui.editor.ReadingPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Checks the notice and frozen native excerpt against a real accepted, fragmented diagram. */
internal fun Instrumentation.verifyReadingSelectionProjectionNotice() {
    val diagram = "flowchart TD\n%% ${"a".repeat(4500)}\nA[Read]-->B[Share]\n"
    val source = "Outside before.\n\n```mermaid\n$diagram```\n\nOutside after."
    val clipboard = checkNotNull(targetContext.getSystemService(ClipboardManager::class.java))
    val sentinel = "Reading selection copy control"
    try {
        withReadingPage(source) { activity, session ->
            val ready = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
            val owner = ready.document.blocks.indexOfFirst { it.kind == MarkdownBlockKind.Code && !it.continuesPrevious }
            check(owner >= 0 && ready.document.blocks[owner + 1].continuesPrevious)
            val request = ready.layout.illustrations.requests(listOf(owner)).single()
            check(request.source == diagram)
            runBlocking {
                withContext(Dispatchers.Main) {
                    withTimeout(15_000) {
                        while (ready.layout.illustrationCache.snapshot()[request] !is IllustrationResult.Rendered) delay(20)
                    }
                    val drawing = ready.layout.illustrationCache.snapshot()[request] as IllustrationResult.Rendered
                    check(drawing.drawing.paths.isNotEmpty())
                    check(drawing.drawing.textRuns.any { it.text == "Share" })
                    val projection = checkNotNull(session.readingDocumentForSelection())
                    check(projection.blocks[owner].text == diagram && projection.blocks[owner + 1].illustrationContinuation)
                    check(session.selectReading(ReadingPoint(owner, 0), ReadingPoint(owner, diagram.length)))
                    check(session.selectedPlainText(128 * 1024).contains("A[Read]-->B[Share]"))
                    session.excerptExport.open(activity, session.title)
                }
            }
            awaitReadingCondition("native Reading excerpt did not freeze") {
                session.excerptExport.capture != null && session.excerptExport.prepared != null && !session.excerptExport.busy
            }
            runOnMainSync {
                val frozen = checkNotNull(session.excerptExport.capture)
                val formatted = checkNotNull(frozen.formatted()).markdown
                check(formatted.contains("A[Read]-->B[Share]"))
                clipboard.setPrimaryClip(ClipData.newPlainText("Selection control", sentinel))
                // Model the cache publication before Compose delivers its UI notification.
                ready.layout.illustrationCache.clear()
                session.copyDocumentSelection { text ->
                    clipboard.setPrimaryClip(ClipData.newPlainText("Selection", text))
                }
                session.onReadingIllustrationsChanged(ready.revision)
                check(session.documentSelection == null)
                check(session.selectionMessage == UiText.Resource(R.string.selection_preview_changed))
                check(session.excerptExport.capture === frozen)
                check(frozen.formatted()?.markdown == formatted)
                session.excerptExport.close()
            }
            waitForAccessibilityNode("preview-change selection notice") {
                it.text?.toString() == "Preview changed. Select the content again."
            }
            check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == sentinel) {
                "Copy delivered a changed Reading projection"
            }
            requireActionableText("Dismiss").performRequiredClick()
            awaitReadingCondition("selection notice did not dismiss") {
                session.selectionMessage == null && session.documentSelection == null
            }
        }
    } finally {
        runOnMainSync { clipboard.clearPrimaryClip() }
    }
}
