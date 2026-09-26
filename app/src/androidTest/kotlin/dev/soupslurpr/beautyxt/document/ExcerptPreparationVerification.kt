package dev.soupslurpr.beautyxt.document

import android.content.Context
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.ui.editor.*
import kotlinx.coroutines.*

/** Delays actual excerpt work so option changes and dismissal cannot hide a capture race. */
internal fun verifyExcerptPreparation(context: Context) = runBlocking {
    suspend fun capture(text: String = "Selected excerpt") = ExcerptCapture(
        7, captureGeneratedExcerpt(text), false, DocumentFormat.PlainText, null, null, text)

    suspend fun ready(controller: ExcerptExportController): PreparedExcerpt = withTimeout(10_000) {
        while (true) {
            val result = withContext(Dispatchers.Main) {
                check(controller.message == null) { "Excerpt preparation failed: ${controller.message}" }
                controller.prepared.takeUnless { controller.busy }
            }
            if (result != null) return@withTimeout result
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    suspend fun previewReady(controller: ExcerptExportController, firstLine: String) = withTimeout(10_000) {
        while (withContext(Dispatchers.Main) { controller.textPreview?.blocks?.firstOrNull()?.text != firstLine }) delay(10)
    }

    suspend fun awaitState(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
    }

    suspend fun exercise(
        captureSelection: suspend () -> ExcerptCapture?,
        renderer: MarkdownRenderer = IsolatedMarkdownRenderer(context),
        action: suspend (ExcerptExportController) -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = ExcerptExportController(scope, captureSelection, { 7 }, { false },
            renderer, null, null, { _, _, _ -> }, { _, _, _, _ -> })
        try { action(controller) }
        finally { withContext(Dispatchers.Main) { controller.close(); scope.cancel() } }
    }

    var captureRetries = 0
    exercise(captureSelection = {
        if (++captureRetries == 1) error("Transient capture failure")
        capture()
    }) { controller ->
        withContext(Dispatchers.Main) { controller.open(context, "Retry.md") }
        awaitState { controller.canRetryPreparation }
        withContext(Dispatchers.Main) { controller.retryPreparation() }
        ready(controller)
        withContext(Dispatchers.Main) { controller.selectDestination(ExcerptDestination.Save) }
        ready(controller)
        withContext(Dispatchers.Main) {
            check(captureRetries == 2 && controller.format == ExcerptFormat.Markdown && controller.canApply)
        }
    }

    var retainedCaptures = 0
    val preparationAttempts = java.util.concurrent.atomic.AtomicInteger()
    val retryRenderer = MarkdownRenderer { snapshot, bytes ->
        if (preparationAttempts.incrementAndGet() == 1) error("Transient rendering failure")
        IsolatedMarkdownRenderer(context).render(snapshot, bytes)
    }
    exercise({ retainedCaptures++; capture() }, retryRenderer) { controller ->
        withContext(Dispatchers.Main) { controller.open(context, "Retry.md") }
        ready(controller)
        withContext(Dispatchers.Main) { controller.selectDestination(ExcerptDestination.Save) }
        awaitState { controller.canRetryPreparation }
        withContext(Dispatchers.Main) {
            controller.updateFileName("Kept name.md")
            controller.retryPreparation()
        }
        ready(controller)
        withContext(Dispatchers.Main) {
            check(retainedCaptures == 1 && preparationAttempts.get() == 2)
            check(controller.fileName == "Kept name.md" && controller.destination == ExcerptDestination.Save && controller.canApply)
        }
    }

    val captureStarted = CompletableDeferred<Unit>()
    val releaseCapture = CompletableDeferred<Unit>()
    exercise(captureSelection = { captureStarted.complete(Unit); releaseCapture.await(); capture() }) { controller ->
        withContext(Dispatchers.Main) { controller.open(context, "Notes.md") }
        withTimeout(10_000) { captureStarted.await() }
        withContext(Dispatchers.Main) {
            check(controller.busy && controller.capture == null && !controller.canApply)
            val initialDraft = controller.printDraft
            val initialName = controller.fileName
            controller.selectDestination(ExcerptDestination.Save)
            controller.selectFormat(ExcerptFormat.Pdf)
            controller.updateShareAsFile(true)
            controller.updateFileName("Early filename.pdf")
            controller.updateTagLabel("EARLY")
            controller.updatePrintDraft(initialDraft.copy(showPageNumbers = !initialDraft.showPageNumbers))
            controller.updatePaperLetter(true)
            check(controller.busy) { "Changing an export option abandoned the pending selection capture" }
            check(controller.destination == ExcerptDestination.Share && controller.format == ExcerptFormat.Text)
            check(!controller.shareAsFile && !controller.paperLetter && controller.tagLabel.isEmpty())
            check(controller.fileName == initialName && controller.printDraft == initialDraft)
        }
        releaseCapture.complete(Unit)
        check(ready(controller).text.metrics.serializedByteLength == "Selected excerpt".length.toLong())
        withContext(Dispatchers.Main) {
            check(controller.canApply)
            controller.selectDestination(ExcerptDestination.Save)
        }
        ready(controller)
        withContext(Dispatchers.Main) {
            check(controller.format == ExcerptFormat.Markdown) { "Captured reading selection lost its Save default" }
            controller.updateShareAsFile(true)
            controller.updateTagLabel("TAG")
            controller.updatePaperLetter(true)
            controller.updatePrintDraft(controller.printDraft.copy(showPageNumbers = false))
            controller.updateFileName("Custom.md")
        }
        ready(controller)
        withContext(Dispatchers.Main) { controller.refresh() }
        ready(controller)
        withContext(Dispatchers.Main) {
            check(controller.destination == ExcerptDestination.Save && controller.format == ExcerptFormat.Markdown)
            check(controller.shareAsFile && controller.paperLetter && controller.tagLabel == "TAG")
            check(controller.fileName == "Custom.md" && !controller.printDraft.showPageNumbers)
        }
        withContext(Dispatchers.Main) { controller.dismiss(); controller.open(context, "Next.md") }
        ready(controller)
        withContext(Dispatchers.Main) {
            check(controller.destination == ExcerptDestination.Share && controller.format == ExcerptFormat.Text) {
                "Reopening export retained the previous destination instead of its defaults"
            }
            check(!controller.shareAsFile && !controller.paperLetter && controller.tagLabel.isEmpty())
            check(controller.printDraft.showPageNumbers)
            check(controller.fileName == "Next excerpt.txt" && controller.canApply)
        }
    }

    val renderStarted = CompletableDeferred<Unit>()
    val renderCancelled = CompletableDeferred<Unit>()
    val slowRenderer = MarkdownRenderer { _, _ ->
        renderStarted.complete(Unit)
        try { awaitCancellation() }
        finally { renderCancelled.complete(Unit) }
    }
    exercise({ capture() }, slowRenderer) { controller ->
        withContext(Dispatchers.Main) { controller.open(context, "Notes.md") }
        ready(controller)
        withContext(Dispatchers.Main) { controller.selectFormat(ExcerptFormat.Markdown) }
        withTimeout(10_000) { renderStarted.await() }
        withContext(Dispatchers.Main) {
            check(controller.busy && controller.capture != null)
            controller.selectFormat(ExcerptFormat.Text)
        }
        withTimeout(10_000) { renderCancelled.await() }
        ready(controller)
        withContext(Dispatchers.Main) { check(controller.format == ExcerptFormat.Text && controller.canApply) }
    }

    // A capture can finish without suspension. Its completed job must not replace
    // the active preparation job which it starts for a refreshed formatted review.
    val refreshRenderStarted = CompletableDeferred<Unit>()
    val refreshRenderCancelled = CompletableDeferred<Unit>()
    val renderCount = java.util.concurrent.atomic.AtomicInteger()
    val isolatedRenderer = IsolatedMarkdownRenderer(context)
    val refreshRenderer = MarkdownRenderer { snapshot, bytes ->
        if (renderCount.incrementAndGet() == 1) isolatedRenderer.render(snapshot, bytes)
        else {
            refreshRenderStarted.complete(Unit)
            try { awaitCancellation() }
            finally { refreshRenderCancelled.complete(Unit) }
        }
    }
    exercise({ capture() }, refreshRenderer) { controller ->
        withContext(Dispatchers.Main) { controller.open(context, "Notes.md") }
        ready(controller)
        withContext(Dispatchers.Main) { controller.selectFormat(ExcerptFormat.Markdown) }
        ready(controller)
        withContext(Dispatchers.Main) { controller.refresh() }
        withTimeout(10_000) { refreshRenderStarted.await() }
        withContext(Dispatchers.Main) { controller.dismiss() }
        check(withTimeoutOrNull(2_000) { refreshRenderCancelled.await(); true } == true) {
            "Dismissing a refreshed excerpt left its renderer running"
        }
        withContext(Dispatchers.Main) {
            check(!controller.visible && !controller.busy && controller.prepared == null && controller.capture == null)
        }
    }

    val oldCaptureStarted = CompletableDeferred<Unit>()
    val oldCaptureCancelled = CompletableDeferred<Unit>()
    var attempts = 0
    exercise(captureSelection = {
        if (++attempts == 1) {
            oldCaptureStarted.complete(Unit)
            try { awaitCancellation() }
            finally { oldCaptureCancelled.complete(Unit) }
        }
        capture("New selection")
    }) { controller ->
        withContext(Dispatchers.Main) { controller.open(context, "Old.md") }
        withTimeout(10_000) { oldCaptureStarted.await() }
        withContext(Dispatchers.Main) { controller.dismiss(); check(!controller.visible && !controller.busy) }
        withTimeout(10_000) { oldCaptureCancelled.await() }
        withContext(Dispatchers.Main) { controller.open(context, "New.md") }
        val payload = ready(controller)
        check(payload.text.snapshot.duplicate().use {
            dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot(it, payload.text.metrics.serializedByteLength)
        } == "New selection")
        withContext(Dispatchers.Main) { check(attempts == 2 && controller.canApply) }
    }

    val pagedText = (1..80).joinToString("\n") { "Line $it" }
    val failPreviewOnce = java.util.concurrent.atomic.AtomicBoolean(true)
    fun retryableSnapshot(snapshot: EditorDocumentSnapshot): EditorDocumentSnapshot = object : EditorDocumentSnapshot by snapshot {
        override fun duplicate() = retryableSnapshot(snapshot.duplicate())
        override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
            if (failPreviewOnce.getAndSet(false)) error("Transient preview failure")
            return snapshot.viewport(cursor, limits)
        }
    }
    exercise(captureSelection = {
        val revision = captureGeneratedExcerpt(pagedText)
        ExcerptCapture(7, CapturedDocumentRevision(revision.metrics, retryableSnapshot(revision.snapshot)),
            true, DocumentFormat.PlainText, null, null)
    }) { controller ->
        withContext(Dispatchers.Main) { controller.open(context, "Retry.txt") }
        ready(controller)
        awaitState { controller.textPreviewFailed && !controller.loadingTextPreview }
        withContext(Dispatchers.Main) { controller.retryTextPreview() }
        previewReady(controller, "Line 1")
        withContext(Dispatchers.Main) {
            check(!controller.textPreviewFailed && !controller.canPreviousPreview && controller.message == null)
            failPreviewOnce.set(true)
            controller.loadTextPreview(true)
        }
        awaitState { controller.textPreviewFailed && !controller.loadingTextPreview }
        withContext(Dispatchers.Main) {
            check(controller.textPreview?.blocks?.first()?.text == "Line 1" && !controller.canPreviousPreview)
            controller.retryTextPreview()
        }
        previewReady(controller, "Line 25")
        withContext(Dispatchers.Main) { check(!controller.textPreviewFailed && controller.canPreviousPreview && controller.canApply) }
    }
    exercise({ capture(pagedText) }) { controller ->
        withContext(Dispatchers.Main) { controller.open(context, "Pages.txt") }
        ready(controller)
        previewReady(controller, "Line 1")
        withContext(Dispatchers.Main) {
            check(!controller.canPreviousPreview && controller.textPreview?.next != null)
            // Both actions arrive before the first request can publish a new page.
            controller.loadTextPreview(true)
            controller.loadTextPreview(true)
        }
        previewReady(controller, "Line 25")
        withContext(Dispatchers.Main) { controller.loadTextPreview(false) }
        check(withTimeoutOrNull(2_000) { previewReady(controller, "Line 1"); true } == true) {
            "Repeated Next created duplicate entries in excerpt preview history"
        }
        withContext(Dispatchers.Main) { check(!controller.canPreviousPreview) }
    }

    // Simulate a native preview read which cannot report its failure until after
    // dismissal and reopening. Its result must belong only to the old review.
    val oldReadStarted = CompletableDeferred<Unit>()
    val oldReadClosed = CompletableDeferred<Unit>()
    val releaseOldRead = java.util.concurrent.CountDownLatch(1)
    fun delayedSnapshot(snapshot: EditorDocumentSnapshot): EditorDocumentSnapshot = object : EditorDocumentSnapshot by snapshot {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        private var delayedRead = false
        override fun duplicate() = delayedSnapshot(snapshot.duplicate())
        override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
            if (cursor.line > 0) {
                delayedRead = true
                oldReadStarted.complete(Unit)
                check(releaseOldRead.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Old preview was not released" }
                error("Delayed old preview failure")
            }
            return snapshot.viewport(cursor, limits)
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                snapshot.close()
                if (delayedRead) oldReadClosed.complete(Unit)
            }
        }
    }
    var previewCaptures = 0
    try {
        exercise(captureSelection = {
            if (++previewCaptures == 1) {
                val revision = captureGeneratedExcerpt(pagedText)
                ExcerptCapture(7, CapturedDocumentRevision(revision.metrics, delayedSnapshot(revision.snapshot)),
                    true, DocumentFormat.PlainText, null, null)
            } else capture("New review")
        }) { controller ->
            withContext(Dispatchers.Main) { controller.open(context, "Old.txt") }
            ready(controller)
            previewReady(controller, "Line 1")
            withContext(Dispatchers.Main) { controller.loadTextPreview(true) }
            withTimeout(10_000) { oldReadStarted.await() }
            withContext(Dispatchers.Main) { controller.dismiss(); controller.open(context, "New.txt") }
            ready(controller)
            previewReady(controller, "New review")
            releaseOldRead.countDown()
            withTimeout(10_000) { oldReadClosed.await() }
            withContext(Dispatchers.Main) {
                check(controller.message == null && !controller.textPreviewFailed) { "An old preview failure changed the new export review" }
                check(controller.textPreview?.blocks?.single()?.text == "New review" && controller.canApply)
            }
        }
    } finally { releaseOldRead.countDown() }
}
